package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.core.TelegramWsProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom

class TelegramWsProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_START = "io.github.lolososka.zapretmobile.TG_WS_START"
        const val ACTION_STOP = "io.github.lolososka.zapretmobile.TG_WS_STOP"
        private const val CHANNEL_ID = "telegram_ws_proxy"
        private const val NOTIFICATION_ID = 205
        private const val PREFS = "telegram_ws_proxy"
        private const val SECRET = "secret"
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun start(context: android.content.Context) {
            val intent = Intent(context, TelegramWsProxyService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, TelegramWsProxyService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }

        fun secretForLink(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val secret = prefs.getString(SECRET, null) ?: ByteArray(16).also {
                SecureRandom().nextBytes(it)
            }.joinToString("") { "%02x".format(it) }.also {
                prefs.edit().putString(SECRET, it).apply()
            }
            return "dd$secret"
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            null -> startProxy()
        }
        return START_STICKY
    }

    private fun startProxy() {
        if (_running.value) return
        val notification = notification(getString(R.string.telegram_ws_starting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val secret = prefs.getString(SECRET, null) ?: createSecret().also {
                prefs.edit().putString(SECRET, it).apply()
            }
            try {
                TelegramWsProxy.configure(
                    poolSize = 4,
                    cacheDir = cacheDir.absolutePath,
                    cloudflare = true,
                    domain = "",
                )
                val result = TelegramWsProxy.start("127.0.0.1", 1443, "", secret)
                if (result == 0) {
                    _running.value = true
                    updateNotification(getString(R.string.telegram_ws_running))
                } else {
                    Log.e("TelegramWsProxy", "StartProxy returned $result")
                    _running.value = false
                    updateNotification(getString(R.string.telegram_ws_failed))
                    stopSelf()
                }
            } catch (error: Throwable) {
                Log.e("TelegramWsProxy", "Failed to start MTProto proxy", error)
                _running.value = false
                updateNotification(getString(R.string.telegram_ws_failed))
                stopSelf()
            }
        }
    }

    private fun stopProxy() {
        scope.launch {
            runCatching { TelegramWsProxy.stop() }
            _running.value = false
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun createSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        if (_running.value) runCatching { TelegramWsProxy.stop() }
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
