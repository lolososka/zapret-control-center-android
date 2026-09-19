package io.github.dovecoteescapee.byedpi.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.core.StrategyProbeCoordinator
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import io.github.dovecoteescapee.byedpi.utility.mode


@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    companion object {
        private val TAG: String = QuickTileService::class.java.simpleName
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onStartListening() {
        updateStatus()
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(STARTED_BROADCAST)
                addAction(STOPPED_BROADCAST)
                addAction(FAILED_BROADCAST)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStopListening() {
        unregisterReceiver(receiver)
    }

    private fun launchActivity(autoStart: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AUTO_START, autoStart)
        TileServiceCompat.startActivityAndCollapse(
            this, PendingIntentActivityWrapper(
                this, if (autoStart) 1 else 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT, false
            )
        )
    }

    override fun onClick() {
        if (qsTile.state == Tile.STATE_UNAVAILABLE) {
            return
        }

        unlockAndRun(this::handleClick)
    }

    private fun setState(newState: Int) {
        qsTile.apply {
            state = newState
            updateTile()
        }
    }

    private fun updateStatus() {
        if (StrategyProbeCoordinator.isActive || ServiceTransitionCoordinator.isActive) {
            setState(Tile.STATE_UNAVAILABLE)
            return
        }
        val (status) = appStatus
        setState(if (status == AppStatus.Halted) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE)
    }

    private fun handleClick() {
        if (StrategyProbeCoordinator.isActive || ServiceTransitionCoordinator.isActive) {
            updateStatus()
            return
        }
        setState(Tile.STATE_ACTIVE)
        setState(Tile.STATE_UNAVAILABLE)

        val (status) = appStatus
        when (status) {
            AppStatus.Halted -> {
                val mode = getPreferences().mode()
                val autoSelected =
                    StrategyProfiles.selected(getPreferences()) == StrategyProfiles.Profile.Auto

                if (mode == Mode.VPN && VpnService.prepare(this) != null) {
                    updateStatus()
                    launchActivity(autoStart = true)
                    return
                }

                if (autoSelected) {
                    updateStatus()
                    launchActivity(autoStart = true)
                    return
                }

                ServiceTransitionCoordinator.beginStart(this, mode)
            }

            AppStatus.Running -> ServiceTransitionCoordinator.beginStop(this, appStatus.second)
        }
    }
}
