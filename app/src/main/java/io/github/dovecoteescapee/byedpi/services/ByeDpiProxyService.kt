package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyUIPreferences
import io.github.dovecoteescapee.byedpi.core.ConnectionDiagnostics
import io.github.dovecoteescapee.byedpi.core.Socks5Health
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var proxySession: ByeDpiProxy.Session? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var destroyed = false
    private val mutex = Mutex()
    private var stopping: Boolean = false
    private var startCommandPending: Boolean = false
    private var stopCommandPending: Boolean = false

    companion object {
        private val TAG: String = ByeDpiProxyService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI Proxy"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        // A recreated service must not inherit a stale in-memory status from a
        // previous instance after Android reclaimed the process.
        status = ServiceStatus.Disconnected
        setStatus(AppStatus.Halted, Mode.Proxy)
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (val action = intent?.action) {
            START_ACTION -> {
                // Enter foreground before native setup so Android's startup deadline
                // cannot kill the service on a slower device.
                startForeground()
                if (!startCommandPending && status != ServiceStatus.Connected) {
                    startCommandPending = true
                    serviceScope.launch {
                        try {
                            start()
                        } finally {
                            startCommandPending = false
                        }
                    }
                }
                START_NOT_STICKY
            }

            STOP_ACTION -> {
                startForeground()
                if (!stopCommandPending) {
                    stopCommandPending = true
                    serviceScope.launch {
                        try {
                            stop()
                        } finally {
                            stopCommandPending = false
                        }
                    }
                }
                START_NOT_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        // LifecycleService cancels lifecycleScope at destruction. Keep teardown
        // alive until the blocking native worker has released its resources.
        serviceScope.launch {
            try {
                stop(if (status == ServiceStatus.Failed) status else ServiceStatus.Disconnected)
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        try {
            mutex.withLock {
                if (destroyed || status == ServiceStatus.Connected) return
                startProxy()
                ConnectionDiagnostics.clear(this@ByeDpiProxyService)
                updateStatus(ServiceStatus.Connected)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            ConnectionDiagnostics.record(this, "Local proxy", e)
            stop(ServiceStatus.Failed)
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop(
        finalStatus: ServiceStatus = ServiceStatus.Disconnected,
        expectedSession: ByeDpiProxy.Session? = null,
    ) {
        Log.i(TAG, "Stopping proxy service")

        mutex.withLock {
            if (expectedSession != null && proxySession !== expectedSession) return
            stopping = true
            try {
                stopProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop proxy", e)
            } finally {
                stopping = false
            }
            updateStatus(finalStatus)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (!destroyed) stopSelf()
        }
    }

    private suspend fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        proxy = ByeDpiProxy()
        val preferences = getByeDpiPreferences()
        val session = withContext(Dispatchers.IO) {
            proxy.prepareProxy(preferences)
        }
        proxySession = session

        proxyJob = serviceScope.launch(Dispatchers.IO) {
            val code = try {
                session.run()
            } catch (e: Exception) {
                Log.e(TAG, "Native proxy loop failed", e)
                -1
            }

            withContext(Dispatchers.Main) {
                if (!stopping && !destroyed && proxySession === session) {
                    Log.e(TAG, "Proxy exited unexpectedly with code $code")
                    ConnectionDiagnostics.record(
                        this@ByeDpiProxyService,
                        "ByeDPI",
                        "native loop exited with code $code",
                    )
                    serviceScope.launch { stop(ServiceStatus.Failed, session) }
                }
            }
        }


        if (preferences is ByeDpiProxyUIPreferences &&
            !Socks5Health.awaitReady(preferences.ip, preferences.port)
        ) {
            throw IllegalStateException("Native SOCKS5 proxy did not become ready")
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        val job = proxyJob
        try {
            proxySession?.stop()
        } finally {
            job?.join()
            proxyJob = null
            proxySession = null
        }

        Log.i(TAG, "Proxy stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "Proxy status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running
                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> AppStatus.Halted
            },
            Mode.Proxy
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.Proxy.ordinal)
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            ByeDpiProxyService::class.java,
        )
}
