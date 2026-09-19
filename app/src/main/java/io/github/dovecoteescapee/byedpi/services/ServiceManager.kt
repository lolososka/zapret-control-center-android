package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.OPERATION_ID
import io.github.dovecoteescapee.byedpi.data.START_ACTION
import io.github.dovecoteescapee.byedpi.data.STOP_ACTION
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.utility.getPreferences

object ServiceManager {
    private val TAG: String = ServiceManager::class.java.simpleName

    fun start(context: Context, mode: Mode, operationId: Long? = null) {
        StrategyProfiles.migrateIfNeeded(context.getPreferences())
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Starting VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java)
                intent.action = START_ACTION
                operationId?.let { intent.putExtra(OPERATION_ID, it) }
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Starting proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java)
                intent.action = START_ACTION
                operationId?.let { intent.putExtra(OPERATION_ID, it) }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    fun stop(context: Context) {
        val (_, mode) = appStatus
        stop(context, mode)
    }

    fun stop(context: Context, mode: Mode, operationId: Long? = null) {
        when (mode) {
            Mode.VPN -> {
                Log.i(TAG, "Stopping VPN")
                val intent = Intent(context, ByeDpiVpnService::class.java)
                intent.action = STOP_ACTION
                operationId?.let { intent.putExtra(OPERATION_ID, it) }
                ContextCompat.startForegroundService(context, intent)
            }

            Mode.Proxy -> {
                Log.i(TAG, "Stopping proxy")
                val intent = Intent(context, ByeDpiProxyService::class.java)
                intent.action = STOP_ACTION
                operationId?.let { intent.putExtra(OPERATION_ID, it) }
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
