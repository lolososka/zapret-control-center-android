package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyUIPreferences
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.core.StrategyProbeCoordinator
import io.github.dovecoteescapee.byedpi.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyProfilesTest {
    @Test
    fun candidatePreferencesStayInMemoryAndPreserveConnectionSettings() {
        val base = ByeDpiProxyUIPreferences(
            ip = "127.0.0.7",
            port = 19080,
            maxConnections = 321,
            bufferSize = 24_000,
            defaultTtl = 42,
            noDomain = true,
            fakeSni = "probe.example",
            oobChar = "z",
            hostMixedCase = true,
            tlsRecordSplitPosition = 9,
            hostsMode = ByeDpiProxyUIPreferences.HostsMode.Blacklist,
            hosts = "example.com",
            tcpFastOpen = true,
            dropSack = true,
        )

        val candidate = StrategyProfiles.candidatePreferences(
            base,
            StrategyProfiles.Profile.Strong,
        )

        assertEquals("127.0.0.7", candidate.ip)
        assertEquals(19080, candidate.port)
        assertEquals(321, candidate.maxConnections)
        assertEquals(24_000, candidate.bufferSize)
        assertTrue(candidate.customTtl)
        assertEquals(42, candidate.defaultTtl)
        assertTrue(candidate.noDomain)
        assertEquals("probe.example", candidate.fakeSni)
        assertEquals('z'.code.toByte(), candidate.oobChar)
        assertTrue(candidate.hostMixedCase)
        assertEquals(ByeDpiProxyUIPreferences.HostsMode.Blacklist, candidate.hostsMode)
        assertEquals("example.com", candidate.hosts)
        assertTrue(candidate.tcpFastOpen)
        assertTrue(candidate.dropSack)

        assertEquals(ByeDpiProxyUIPreferences.DesyncMethod.Fake, candidate.desyncMethod)
        assertEquals(-1, candidate.splitPosition)
        assertTrue(candidate.tlsRecordSplit)
        assertEquals(1, candidate.tlsRecordSplitPosition)
        assertTrue(candidate.tlsRecordSplitAtSni)
        assertTrue(candidate.desyncUdp)
        assertEquals(1, candidate.udpFakeCount)
        assertFalse(candidate.splitAtHost)
    }

    @Test
    fun coordinatorKeepsOneProcessWideProbeAndRetainsPermissionRequest() {
        val request = StrategyProbeCoordinator.Request(
            mode = Mode.VPN,
            shouldRunAfterProbe = true,
        )
        StrategyProbeCoordinator.finish()
        try {
            assertTrue(StrategyProbeCoordinator.tryBegin(request))
            assertTrue(StrategyProbeCoordinator.isActive)
            assertTrue(StrategyProbeCoordinator.active.value)
            assertFalse(StrategyProbeCoordinator.tryBegin())
            assertEquals(request, StrategyProbeCoordinator.takePermissionRequest())
            assertEquals(null, StrategyProbeCoordinator.takePermissionRequest())
        } finally {
            StrategyProbeCoordinator.finish()
        }
        assertFalse(StrategyProbeCoordinator.isActive)
        assertFalse(StrategyProbeCoordinator.active.value)
        assertTrue(StrategyProbeCoordinator.tryBegin())
        StrategyProbeCoordinator.finish()
    }
}
