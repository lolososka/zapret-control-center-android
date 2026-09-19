package io.github.dovecoteescapee.byedpi

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.github.dovecoteescapee.byedpi.data.FAILED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.OPERATION_ID
import io.github.dovecoteescapee.byedpi.data.SENDER
import io.github.dovecoteescapee.byedpi.data.STARTED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.STOPPED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.Sender
import io.github.dovecoteescapee.byedpi.services.ServiceTransitionCoordinator

/** Keeps service transactions alive while activities are recreated or absent. */
class ZapretApplication : Application() {
    private val transitionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sender = Sender.entries.getOrNull(intent.getIntExtra(SENDER, -1)) ?: return
            ServiceTransitionCoordinator.onServiceEvent(
                context = this@ZapretApplication,
                action = intent.action ?: return,
                mode = Mode.fromSender(sender),
                operationId = intent.getLongExtra(OPERATION_ID, 0L),
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            transitionReceiver,
            IntentFilter().apply {
                addAction(STARTED_BROADCAST)
                addAction(STOPPED_BROADCAST)
                addAction(FAILED_BROADCAST)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
