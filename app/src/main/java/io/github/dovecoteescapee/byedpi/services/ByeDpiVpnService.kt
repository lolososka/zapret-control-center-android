package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.core.TProxyService
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
import java.io.File

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var proxySession: ByeDpiProxy.Session? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var destroyed = false
    private var tunFd: ParcelFileDescriptor? = null
    private var tunConfigFile: File? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false
    private var startCommandPending: Boolean = false
    private var stopCommandPending: Boolean = false

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        // A recreated service must not inherit a stale in-memory status from a
        // previous instance after Android reclaimed the process.
        status = ServiceStatus.Disconnected
        setStatus(AppStatus.Halted, Mode.VPN)
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (val action = intent?.action) {
            START_ACTION -> {
                // Android requires a service launched through startForegroundService()
                // to become foreground promptly, before native startup work begins.
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
                // STOP can also arrive through a foreground-service PendingIntent.
                // Publishing the notification first keeps this path valid even if the
                // process/service was recreated before handling the command.
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

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        serviceScope.launch { stop() }
    }

    override fun onDestroy() {
        destroyed = true
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
                startTun2Socks()
                updateStatus(ServiceStatus.Connected)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stop(ServiceStatus.Failed)
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop(
        finalStatus: ServiceStatus = ServiceStatus.Disconnected,
        expectedSession: ByeDpiProxy.Session? = null,
    ) {
        Log.i(TAG, "Stopping")

        mutex.withLock {
            if (expectedSession != null && proxySession !== expectedSession) return
            stopping = true
            try {
                try {
                    stopTun2Socks()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop tun2socks", e)
                }
                try {
                    stopProxy()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop proxy", e)
                }
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

        val preferences = getByeDpiPreferences()
        val session = withContext(Dispatchers.IO) {
            byeDpiProxy.prepareProxy(preferences)
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
                    // Run teardown in a different coroutine. Calling stop() from
                    // proxyJob itself would make stopProxy() join the current job.
                    serviceScope.launch { stop(ServiceStatus.Failed, session) }
                }
            }
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

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val port = sharedPreferences.getString("byedpi_proxy_port", null)?.toInt() ?: 1080
        val dns = sharedPreferences.getStringNotNull("dns_ip", "1.1.1.1")
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)

        val tun2socksConfig = """
        | misc:
        |   task-stack-size: 81920
        | socks5:
        |   mtu: 8500
        |   address: 127.0.0.1
        |   port: $port
        |   udp: udp
        """.trimMargin("| ")

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }
        tunConfigFile = configPath

        val fd = createBuilder(dns, ipv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)

        Log.i(TAG, "Tun2Socks started")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        val fd = tunFd
        val configFile = tunConfigFile
        tunFd = null
        tunConfigFile = null

        var failure: Exception? = null
        fun cleanup(step: String, action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to $step", e)
                if (failure == null) {
                    failure = e
                } else {
                    failure?.addSuppressed(e)
                }
            }
        }

        if (fd == null) {
            Log.w(TAG, "VPN is not running")
        } else {
            cleanup("stop native tun2socks") { TProxyService.TProxyStopService() }
            cleanup("close VPN descriptor") { fd.close() }
        }

        if (configFile != null) {
            cleanup("delete tun2socks config") {
                if (configFile.exists() && !configFile.delete()) {
                    throw IllegalStateException("Could not delete ${configFile.absolutePath}")
                }
            }
        }

        failure?.let { throw it }
        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> AppStatus.Halted
            },
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            ByeDpiVpnService::class.java,
        )

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        builder.setSession(getString(R.string.app_name))
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        builder.addDisallowedApplication(applicationContext.packageName)

        return builder
    }
}
