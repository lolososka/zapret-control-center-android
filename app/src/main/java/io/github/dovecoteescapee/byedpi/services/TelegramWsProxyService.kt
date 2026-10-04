package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.core.ConnectionDiagnostics
import io.github.dovecoteescapee.byedpi.core.TelegramProxyPortSelector
import io.github.dovecoteescapee.byedpi.core.TelegramWsProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.Job

class TelegramWsProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Native StartProxy/StopProxy are process-global and must never overlap. */
    private val operationMutex = Mutex()
    private var operationJob: Job? = null
    private var stopQueued = false
    private var destroyed = false

    companion object {
        const val ACTION_START = "io.github.lolososka.zapretmobile.TG_WS_START"
        const val ACTION_STOP = "io.github.lolososka.zapretmobile.TG_WS_STOP"
        private const val CHANNEL_ID = "telegram_ws_proxy"
        private const val NOTIFICATION_ID = 205
        private const val PREFS = "telegram_ws_proxy"
        private const val SECRET = "secret"
        private const val PORT = "port"
        private val DEFAULT_PORT = TelegramProxyPortSelector.DEFAULT_PORT
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _starting = MutableStateFlow(false)
        val starting: StateFlow<Boolean> = _starting
        private val _port = MutableStateFlow(DEFAULT_PORT)
        val port: StateFlow<Int> = _port

        fun start(context: android.content.Context) {
            if (_running.value || _starting.value) return
            _starting.value = true
            val intent = Intent(context, TelegramWsProxyService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: RuntimeException) {
                _starting.value = false
                throw error
            }
        }

        fun stop(context: android.content.Context) {
            // Keep the UI in a busy state until the serialized native teardown
            // has completed; otherwise a quick second tap can enqueue START.
            _starting.value = true
            val intent = Intent(context, TelegramWsProxyService::class.java).setAction(ACTION_STOP)
            // The app is in the foreground when this is called. Starting a
            // normal service here avoids creating a second foreground start
            // just to deliver a stop command.
            context.startService(intent)
        }

        suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
            if (_running.value) return true
            return withTimeoutOrNull(timeoutMs) {
                running.filter { it }.first()
                true
            } ?: false
        }

        fun secretForLink(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val stored = prefs.getString(SECRET, null)
            val secret = stored.takeIf { isValidSecret(it) } ?: generateSecret().also {
                prefs.edit().putString(SECRET, it).apply()
            }
            return "dd$secret"
        }

        fun portForLink(context: Context): Int {
            if (_running.value) return _port.value
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val stored = runCatching { prefs.getInt(PORT, DEFAULT_PORT) }
                .getOrDefault(DEFAULT_PORT)
            val port = TelegramProxyPortSelector.sanitize(stored)
            if (stored != port) prefs.edit().putInt(PORT, port).apply()
            return port
        }

        private fun isValidSecret(value: String?): Boolean =
            value?.length == 32 && value.all { it in "0123456789abcdefABCDEF" }

        private fun generateSecret(): String = ByteArray(16).also {
            SecureRandom().nextBytes(it)
        }.joinToString("") { "%02x".format(it) }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (destroyed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            null -> startProxy()
        }
        return START_STICKY
    }

    private fun startProxy() {
        // The companion start() marks _starting before Android delivers the
        // intent. Use the actual queued job as the duplicate guard; checking
        // _starting here would reject the very first start request.
        if (destroyed || _running.value || operationJob?.isActive == true || stopQueued) return
        _starting.value = true
        val notification = notification(getString(R.string.telegram_ws_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        operationJob = scope.launch {
            operationMutex.withLock {
                startProxyLocked()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (operationJob === job) operationJob = null
            }
        }
    }

    /** Runs under [operationMutex], so a stop can never race native startup. */
    private suspend fun startProxyLocked() {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            var nativeStarted = false
            var selectedPort: Int? = null
            var lastResult = -3
            try {
                val secret = prefs.getString(SECRET, null).takeIf { isValidSecret(it) } ?: createSecret().also {
                    prefs.edit().putString(SECRET, it).apply()
                }
                TelegramWsProxy.configure(
                    poolSize = 4,
                    cacheDir = cacheDir.absolutePath,
                    cloudflare = true,
                    domain = "",
                )
                val storedPort = runCatching { prefs.getInt(PORT, DEFAULT_PORT) }
                    .getOrDefault(DEFAULT_PORT)
                val attemptedPorts = TelegramProxyPortSelector.candidates(
                    storedPort,
                    findAvailablePorts(7),
                )

                for (candidatePort in attemptedPorts) {
                    if (!currentCoroutineContext().isActive) throw CancellationException()
                    lastResult = TelegramWsProxy.start("127.0.0.1", candidatePort, "", secret)
                    if (lastResult != 0) continue
                    nativeStarted = true
                    if (waitForPort(candidatePort)) {
                        selectedPort = candidatePort
                        break
                    }
                    runCatching { TelegramWsProxy.stop() }
                    nativeStarted = false
                }

                if (selectedPort != null) {
                    prefs.edit().putInt(PORT, selectedPort).apply()
                    _port.value = selectedPort
                    ConnectionDiagnostics.clear(this@TelegramWsProxyService)
                    _running.value = true
                    _starting.value = false
                    updateNotification(getString(R.string.telegram_ws_running, selectedPort))
                } else {
                    Log.e("TelegramWsProxy", "StartProxy returned $lastResult for ports $attemptedPorts")
                    ConnectionDiagnostics.record(
                        this@TelegramWsProxyService,
                        "Telegram MTProto",
                        "native start returned $lastResult; unable to bind a local port",
                    )
                    updateNotification(getString(R.string.telegram_ws_failed))
                    stopSelf()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e("TelegramWsProxy", "Failed to start MTProto proxy", error)
                ConnectionDiagnostics.record(this@TelegramWsProxyService, "Telegram MTProto", error)
                updateNotification(getString(R.string.telegram_ws_failed))
                stopSelf()
            } finally {
                if (selectedPort == null && nativeStarted) {
                    withContext(NonCancellable) {
                        runCatching { TelegramWsProxy.stop() }
                    }
                }
                if (selectedPort == null) _running.value = false
                _starting.value = false
            }
    }

    private suspend fun waitForPort(port: Int): Boolean {
        repeat(20) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                }
                return true
            } catch (_: Exception) {
                delay(100)
            }
        }
        return false
    }

    private fun findAvailablePorts(count: Int): List<Int> {
        val reservations = mutableListOf<ServerSocket>()
        return try {
            repeat(count) {
                reservations += ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            }
            reservations.map { it.localPort }
        } catch (_: Exception) {
            reservations.map { it.localPort }
        } finally {
            reservations.forEach { runCatching { it.close() } }
        }
    }

    private fun stopProxy() {
        if (stopQueued) return
        stopQueued = true
        // Treat teardown as busy too. This prevents a rapid second tap from
        // enqueueing a new native StartProxy before StopProxy has completed.
        _starting.value = true
        operationJob = scope.launch {
            operationMutex.withLock {
                try {
                    withContext(NonCancellable) {
                        runCatching { TelegramWsProxy.stop() }
                    }
                } finally {
                    _running.value = false
                    _starting.value = false
                    stopQueued = false
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                    stopSelf()
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (operationJob === job) operationJob = null
            }
        }
    }

    private fun createSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.telegram_ws_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager().notify(NOTIFICATION_ID, notification(text))
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        destroyed = true
        operationJob?.cancel()
        _running.value = false
        _starting.value = false
        // Keep cleanup outside the cancelled start job and serialize it with
        // any in-flight native call. Android may recreate the service after
        // process pressure, so leaving the global Rust state half-open here is
        // worse than a short asynchronous teardown.
        scope.launch(NonCancellable) {
            operationMutex.withLock {
                runCatching { TelegramWsProxy.stop() }
            }
            scope.cancel()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
