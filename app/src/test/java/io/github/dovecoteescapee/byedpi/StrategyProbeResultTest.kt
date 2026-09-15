package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.StrategyProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyProbeResultTest {
    @Test
    fun twoDiscordHostsCountAsOneProduct() {
        val result = StrategyProbeResult.fromEndpointResults(listOf(
            "discord" to 20L,
            "discord" to 30L,
            "youtube" to 40L,
            "telegram" to null,
        ))

        assertEquals(3, result.successes)
        assertEquals(2, result.products)
        assertEquals(90L, result.latencyMs)
        assertFalse(result.isEligible(3))
    }

    @Test
    fun allThreeProductsQualifyEvenWhenOneDiscordHostFails() {
        val result = StrategyProbeResult.fromEndpointResults(listOf(
            "discord" to null,
            "discord" to 30L,
            "youtube" to 40L,
            "telegram" to 50L,
        ))

        assertEquals(3, result.successes)
        assertEquals(3, result.products)
        assertEquals(120L, result.latencyMs)
        assertTrue(result.isEligible(3))
    }

    @Test
    fun totalFailureCannotQualifyAsFastestStrategy() {
        val result = StrategyProbeResult.fromEndpointResults(listOf(
            "discord" to null,
            "youtube" to null,
            "telegram" to null,
        ))

        assertEquals(StrategyProbeResult(0, 0, 0L), result)
        assertFalse(result.isEligible(3))
        assertFalse(StrategyProbeResult.fromEndpointResults(emptyList()).isEligible(3))
    }

    @Test
    fun moreSuccessfulHostsWinBeforeLatency() {
        val slowerComplete = StrategyProbeResult(4, 3, 900L)
        val fasterPartial = StrategyProbeResult(3, 3, 30L)

        assertTrue(slowerComplete.isBetterThan(fasterPartial))
        assertFalse(fasterPartial.isBetterThan(slowerComplete))
    }

    @Test
    fun equalSuccessCountsPreferLowerLatencyAndKeepTiesStable() {
        val faster = StrategyProbeResult(3, 3, 30L)
        val slower = StrategyProbeResult(3, 3, 90L)

        assertTrue(faster.isBetterThan(slower))
        assertFalse(slower.isBetterThan(faster))
        assertFalse(faster.isBetterThan(faster.copy()))
        assertTrue(faster.isBetterThan(null))
    }
}
