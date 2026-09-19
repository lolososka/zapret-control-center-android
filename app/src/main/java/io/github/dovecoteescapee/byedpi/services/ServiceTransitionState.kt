package io.github.dovecoteescapee.byedpi.services

import io.github.dovecoteescapee.byedpi.data.FAILED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.STARTED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.STOPPED_BROADCAST
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure state machine behind [ServiceTransitionCoordinator]. */
internal class ServiceTransitionState {
    sealed interface Command {
        val operationId: Long
        val mode: Mode

        data class Stop(
            override val operationId: Long,
            override val mode: Mode,
        ) : Command

        data class Start(
            override val operationId: Long,
            override val mode: Mode,
        ) : Command
    }

    data class Effect(
        val command: Command? = null,
        val modeToPersist: Mode? = null,
    )

    data class ProbeStop(
        val id: Long,
        val stopped: Deferred<Unit>,
        val command: Command.Stop,
    )

    data class AbandonResult(
        val handled: Boolean,
        val command: Command.Start? = null,
    )

    private enum class Phase { AwaitingPermission, Stopping, ProbeStopping, ProbeStopped, Starting }

    private data class Transition(
        val id: Long,
        var phase: Phase,
        val stopMode: Mode?,
        val startMode: Mode?,
        val persistStartMode: Boolean,
        val stoppedSignal: CompletableDeferred<Unit>? = null,
        var recoverWhenStopped: Boolean = false,
    )

    private val ids = AtomicLong(1L)
    private val lock = Any()
    private val _active = MutableStateFlow(false)
    private var transition: Transition? = null

    val active = _active.asStateFlow()
    val isActive: Boolean
        get() = synchronized(lock) { transition != null }

    fun beginRestart(
        stopMode: Mode,
        startMode: Mode,
        persistStartMode: Boolean,
    ): Command.Stop? {
        val current = newTransition(
            phase = Phase.Stopping,
            stopMode = stopMode,
            startMode = startMode,
            persistStartMode = persistStartMode,
        ) ?: return null
        return Command.Stop(current.id, stopMode)
    }

    fun beginStop(stopMode: Mode): Command.Stop? {
        val current = newTransition(
            phase = Phase.Stopping,
            stopMode = stopMode,
            startMode = null,
            persistStartMode = false,
        ) ?: return null
        return Command.Stop(current.id, stopMode)
    }

    fun prepareRestartAfterPermission(
        stopMode: Mode,
        startMode: Mode,
        persistStartMode: Boolean,
    ): Boolean = newTransition(
        phase = Phase.AwaitingPermission,
        stopMode = stopMode,
        startMode = startMode,
        persistStartMode = persistStartMode,
    ) != null

    fun prepareStartAfterPermission(
        startMode: Mode,
        persistStartMode: Boolean,
    ): Boolean = newTransition(
        phase = Phase.AwaitingPermission,
        stopMode = null,
        startMode = startMode,
        persistStartMode = persistStartMode,
    ) != null

    fun startPrepared(): Effect? = synchronized(lock) {
        val current = transition
            ?.takeIf { it.phase == Phase.AwaitingPermission }
            ?: return@synchronized null
        val stopMode = current.stopMode
        if (stopMode != null) {
            current.phase = Phase.Stopping
            Effect(command = Command.Stop(current.id, stopMode))
        } else {
            val startMode = requireNotNull(current.startMode)
            current.phase = Phase.Starting
            Effect(
                command = Command.Start(current.id, startMode),
                modeToPersist = startMode.takeIf { current.persistStartMode },
            )
        }
    }

    fun cancelPrepared(): Boolean = synchronized(lock) {
        val current = transition ?: return@synchronized false
        if (current.phase != Phase.AwaitingPermission) return@synchronized false
        finishLocked(current.id)
        true
    }

    fun beginStart(startMode: Mode, persistStartMode: Boolean): Effect? {
        val current = newTransition(
            phase = Phase.Starting,
            stopMode = null,
            startMode = startMode,
            persistStartMode = persistStartMode,
        ) ?: return null
        return Effect(
            command = Command.Start(current.id, startMode),
            modeToPersist = startMode.takeIf { persistStartMode },
        )
    }

    fun beginProbeStop(stopMode: Mode, recoveryMode: Mode): ProbeStop? {
        val signal = CompletableDeferred<Unit>()
        val current = newTransition(
            phase = Phase.ProbeStopping,
            stopMode = stopMode,
            startMode = recoveryMode,
            persistStartMode = true,
            stoppedSignal = signal,
        ) ?: return null
        return ProbeStop(
            id = current.id,
            stopped = signal,
            command = Command.Stop(current.id, stopMode),
        )
    }

    fun claimProbeStop(id: Long): Boolean = synchronized(lock) {
        val current = transition ?: return@synchronized false
        if (current.id != id || current.phase != Phase.ProbeStopped) return@synchronized false
        finishLocked(id)
        true
    }

    fun abandonProbeStop(id: Long): AbandonResult = synchronized(lock) {
        val current = transition ?: return@synchronized AbandonResult(false)
        if (current.id != id) return@synchronized AbandonResult(false)
        when (current.phase) {
            Phase.ProbeStopping -> current.recoverWhenStopped = true
            Phase.ProbeStopped -> {
                current.phase = Phase.Starting
                return@synchronized AbandonResult(
                    handled = true,
                    command = Command.Start(current.id, requireNotNull(current.startMode)),
                )
            }
            Phase.Starting -> Unit
            else -> return@synchronized AbandonResult(false)
        }
        AbandonResult(true)
    }

    fun onServiceEvent(action: String, mode: Mode, operationId: Long): Effect {
        if (operationId <= 0L) return Effect()
        return synchronized(lock) {
            val current = transition ?: return@synchronized Effect()
            if (current.id != operationId) return@synchronized Effect()
            when (current.phase) {
                Phase.Stopping -> {
                    if (mode != current.stopMode) return@synchronized Effect()
                    when (action) {
                        STOPPED_BROADCAST -> {
                            val target = current.startMode
                            if (target == null) {
                                finishLocked(current.id)
                                Effect()
                            } else {
                                current.phase = Phase.Starting
                                Effect(
                                    command = Command.Start(current.id, target),
                                    modeToPersist = target.takeIf { current.persistStartMode },
                                )
                            }
                        }
                        FAILED_BROADCAST -> {
                            finishLocked(current.id)
                            Effect()
                        }
                        else -> Effect()
                    }
                }

                Phase.ProbeStopping -> {
                    if (mode != current.stopMode ||
                        action !in setOf(STOPPED_BROADCAST, FAILED_BROADCAST)
                    ) return@synchronized Effect()
                    if (current.recoverWhenStopped) {
                        current.phase = Phase.Starting
                        val target = requireNotNull(current.startMode)
                        Effect(
                            command = Command.Start(current.id, target),
                            modeToPersist = target.takeIf { current.persistStartMode },
                        )
                    } else {
                        current.phase = Phase.ProbeStopped
                        current.stoppedSignal?.complete(Unit)
                        Effect()
                    }
                }

                Phase.Starting -> {
                    if (mode != current.startMode) return@synchronized Effect()
                    if (action == STARTED_BROADCAST || action == FAILED_BROADCAST) {
                        finishLocked(current.id)
                    }
                    Effect()
                }

                Phase.AwaitingPermission,
                Phase.ProbeStopped -> Effect()
            }
        }
    }

    fun fail(operationId: Long) {
        synchronized(lock) { finishLocked(operationId) }
    }

    /** Expires only the exact command that was dispatched for the current phase. */
    fun timeout(command: Command): Boolean = synchronized(lock) {
        val current = transition ?: return@synchronized false
        if (current.id != command.operationId) return@synchronized false
        val stillWaiting = when (command) {
            is Command.Stop ->
                current.phase in setOf(Phase.Stopping, Phase.ProbeStopping) &&
                    current.stopMode == command.mode
            is Command.Start ->
                current.phase == Phase.Starting && current.startMode == command.mode
        }
        if (!stillWaiting) return@synchronized false
        finishLocked(current.id)
        true
    }

    private fun newTransition(
        phase: Phase,
        stopMode: Mode?,
        startMode: Mode?,
        persistStartMode: Boolean,
        stoppedSignal: CompletableDeferred<Unit>? = null,
    ): Transition? = synchronized(lock) {
        if (transition != null) return@synchronized null
        val current = Transition(
            id = ids.getAndIncrement().coerceAtLeast(1L),
            phase = phase,
            stopMode = stopMode,
            startMode = startMode,
            persistStartMode = persistStartMode,
            stoppedSignal = stoppedSignal,
        )
        transition = current
        _active.value = true
        current
    }

    private fun finishLocked(operationId: Long) {
        val current = transition ?: return
        if (current.id != operationId) return
        current.stoppedSignal?.let { signal ->
            if (!signal.isCompleted) signal.completeExceptionally(
                IllegalStateException("Service transition failed"),
            )
        }
        transition = null
        _active.value = false
    }
}
