package io.github.dovecoteescapee.byedpi.services

import android.content.Context
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Deferred

/**
 * Owns service stop/start transactions outside any Activity.
 *
 * Every command carries a generation id echoed by the service, so a stale
 * broadcast from an earlier service instance cannot consume a newer action.
 */
object ServiceTransitionCoordinator {
    private const val COMMAND_TIMEOUT_SECONDS = 20L

    data class ProbeStop internal constructor(
        val id: Long,
        val stopped: Deferred<Unit>,
    )

    private val state = ServiceTransitionState()
    private val watchdog = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "service-transition-watchdog").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }

    val active = state.active
    val isActive: Boolean
        get() = state.isActive

    fun beginRestart(
        context: Context,
        stopMode: Mode,
        startMode: Mode,
        persistStartMode: Boolean = true,
    ): Boolean {
        val command = state.beginRestart(
            stopMode = stopMode,
            startMode = startMode,
            persistStartMode = persistStartMode,
        ) ?: return false
        return dispatch(context, command)
    }

    fun beginStop(context: Context, stopMode: Mode): Boolean {
        val command = state.beginStop(stopMode) ?: return false
        return dispatch(context, command)
    }

    fun prepareRestartAfterPermission(
        stopMode: Mode,
        startMode: Mode,
        persistStartMode: Boolean = true,
    ): Boolean = state.prepareRestartAfterPermission(
        stopMode = stopMode,
        startMode = startMode,
        persistStartMode = persistStartMode,
    )

    fun prepareStartAfterPermission(
        startMode: Mode,
        persistStartMode: Boolean = true,
    ): Boolean = state.prepareStartAfterPermission(
        startMode = startMode,
        persistStartMode = persistStartMode,
    )

    fun startPrepared(context: Context): Boolean {
        val effect = state.startPrepared() ?: return false
        effect.modeToPersist?.let { persistMode(context, it) }
        return dispatch(context, requireNotNull(effect.command))
    }

    fun cancelPrepared(): Boolean = state.cancelPrepared()

    fun beginStart(
        context: Context,
        startMode: Mode,
        persistStartMode: Boolean = true,
    ): Boolean {
        val effect = state.beginStart(startMode, persistStartMode) ?: return false
        effect.modeToPersist?.let { persistMode(context, it) }
        return dispatch(context, requireNotNull(effect.command))
    }

    fun beginProbeStop(
        context: Context,
        stopMode: Mode,
        recoveryMode: Mode,
    ): ProbeStop? {
        val probe = state.beginProbeStop(stopMode, recoveryMode) ?: return null
        if (!dispatch(context, probe.command)) return null
        return ProbeStop(probe.id, probe.stopped)
    }

    /** Claims a completed probe stop so the caller can use the released native listener. */
    fun claimProbeStop(id: Long): Boolean = state.claimProbeStop(id)

    /** Ensures a cancelled/timed-out probe eventually restores its original mode. */
    fun abandonProbeStop(context: Context, id: Long): Boolean {
        val result = state.abandonProbeStop(id)
        result.command?.let { dispatch(context, it) }
        return result.handled
    }

    fun onServiceEvent(
        context: Context,
        action: String,
        mode: Mode,
        operationId: Long,
    ) {
        val effect = state.onServiceEvent(action, mode, operationId)
        effect.modeToPersist?.let { modeToPersist -> persistMode(context, modeToPersist) }
        effect.command?.let { dispatch(context, it) }
    }

    private fun dispatch(context: Context, command: ServiceTransitionState.Command): Boolean = try {
        when (command) {
            is ServiceTransitionState.Command.Stop -> ServiceManager.stop(
                context.applicationContext,
                command.mode,
                command.operationId,
            )
            is ServiceTransitionState.Command.Start -> ServiceManager.start(
                context.applicationContext,
                command.mode,
                command.operationId,
            )
        }
        watchdog.schedule(
            { state.timeout(command) },
            COMMAND_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        true
    } catch (_: Exception) {
        state.fail(command.operationId)
        false
    }

    private fun persistMode(context: Context, mode: Mode) {
        context.getPreferences().edit()
            .putString("byedpi_mode", mode.name.lowercase(Locale.ROOT))
            .apply()
    }
}
