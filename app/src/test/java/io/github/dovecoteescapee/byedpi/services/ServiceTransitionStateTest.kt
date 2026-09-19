package io.github.dovecoteescapee.byedpi.services

import io.github.dovecoteescapee.byedpi.data.FAILED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.STARTED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.STOPPED_BROADCAST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTransitionStateTest {
    @Test
    fun restartOnlyAdvancesForMatchingOperationAndMode() {
        val state = ServiceTransitionState()
        val stop = requireNotNull(
            state.beginRestart(
                stopMode = Mode.Proxy,
                startMode = Mode.VPN,
                persistStartMode = true,
            ),
        )

        assertEquals(1L, stop.operationId)
        assertTrue(state.isActive)
        assertTrue(state.active.value)
        assertNull(state.beginStart(Mode.Proxy, persistStartMode = false))

        assertNull(
            state.onServiceEvent(STOPPED_BROADCAST, Mode.Proxy, 0L).command,
        )
        assertNull(
            state.onServiceEvent(STOPPED_BROADCAST, Mode.Proxy, stop.operationId + 1L).command,
        )
        assertNull(
            state.onServiceEvent(STOPPED_BROADCAST, Mode.VPN, stop.operationId).command,
        )
        assertTrue(state.isActive)

        val start = state.onServiceEvent(
            STOPPED_BROADCAST,
            Mode.Proxy,
            stop.operationId,
        )
        assertEquals(Mode.VPN, start.modeToPersist)
        assertEquals(
            ServiceTransitionState.Command.Start(stop.operationId, Mode.VPN),
            start.command,
        )

        state.onServiceEvent(STARTED_BROADCAST, Mode.Proxy, stop.operationId)
        state.onServiceEvent(STARTED_BROADCAST, Mode.VPN, stop.operationId + 1L)
        assertTrue(state.isActive)

        state.onServiceEvent(STARTED_BROADCAST, Mode.VPN, stop.operationId)
        assertFalse(state.isActive)
        assertFalse(state.active.value)

        val nextStart = requireNotNull(state.beginStart(Mode.Proxy, persistStartMode = false))
        val nextCommand = requireNotNull(nextStart.command)
        assertEquals(stop.operationId + 1L, nextCommand.operationId)
        state.onServiceEvent(FAILED_BROADCAST, Mode.Proxy, stop.operationId)
        assertTrue(state.isActive)
        state.onServiceEvent(FAILED_BROADCAST, Mode.Proxy, nextCommand.operationId)
        assertFalse(state.isActive)
    }

    @Test
    fun preparedRestartCanOnlyBeStartedOrCancelledOnce() {
        val state = ServiceTransitionState()

        assertTrue(
            state.prepareRestartAfterPermission(
                stopMode = Mode.VPN,
                startMode = Mode.Proxy,
                persistStartMode = false,
            ),
        )
        assertFalse(
            state.prepareRestartAfterPermission(
                stopMode = Mode.VPN,
                startMode = Mode.Proxy,
                persistStartMode = false,
            ),
        )
        assertTrue(state.cancelPrepared())
        assertFalse(state.cancelPrepared())
        assertFalse(state.isActive)

        assertTrue(
            state.prepareRestartAfterPermission(
                stopMode = Mode.VPN,
                startMode = Mode.Proxy,
                persistStartMode = false,
            ),
        )
        val stop = requireNotNull(state.startPrepared()).command as ServiceTransitionState.Command.Stop
        assertEquals(Mode.VPN, stop.mode)
        assertFalse(state.cancelPrepared())
        assertNull(state.startPrepared())

        state.onServiceEvent(FAILED_BROADCAST, Mode.VPN, stop.operationId)
        assertFalse(state.isActive)
    }

    @Test
    fun staleCommandTimeoutCannotFinishAChangedPhaseOrNewOperation() {
        val state = ServiceTransitionState()
        val stop = requireNotNull(
            state.beginRestart(
                stopMode = Mode.Proxy,
                startMode = Mode.VPN,
                persistStartMode = false,
            ),
        )
        val start = requireNotNull(
            state.onServiceEvent(STOPPED_BROADCAST, Mode.Proxy, stop.operationId).command,
        )

        assertFalse(state.timeout(stop))
        assertTrue(state.isActive)
        state.onServiceEvent(STARTED_BROADCAST, Mode.VPN, start.operationId)
        assertFalse(state.isActive)

        val next = requireNotNull(state.beginStop(Mode.Proxy))
        assertFalse(state.timeout(start))
        assertTrue(state.isActive)
        assertTrue(state.timeout(next))
        assertFalse(state.isActive)
    }

    @Test
    fun probeStopCompletesAndCanBeClaimedOnlyByItsOperation() {
        val state = ServiceTransitionState()
        val probe = requireNotNull(
            state.beginProbeStop(
                stopMode = Mode.Proxy,
                recoveryMode = Mode.VPN,
            ),
        )

        assertFalse(probe.stopped.isCompleted)
        state.onServiceEvent(STOPPED_BROADCAST, Mode.Proxy, probe.id + 1L)
        state.onServiceEvent(STOPPED_BROADCAST, Mode.VPN, probe.id)
        assertFalse(probe.stopped.isCompleted)
        assertTrue(state.isActive)

        state.onServiceEvent(STOPPED_BROADCAST, Mode.Proxy, probe.id)
        assertTrue(probe.stopped.isCompleted)
        assertTrue(state.isActive)
        assertFalse(state.claimProbeStop(probe.id + 1L))
        assertTrue(state.isActive)
        assertTrue(state.claimProbeStop(probe.id))
        assertFalse(state.isActive)
        assertFalse(state.claimProbeStop(probe.id))
    }
}
