package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.StrategyProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyProbeResultTest {
    private val requiredProducts = setOf("discord-core", "youtube-core")
    private val mediaProducts = setOf("discord-media", "youtube-media")

    @Test
    fun frontendWithoutUdpDoesNotQualify() {
        val result = StrategyProbeResult.fromEndpointResults(
            listOf(
                "discord-core" to 20L,
                "youtube-core" to 40L,
                "telegram" to 30L,
            ),
            udpLatencyMs = null,
        )

        assertEquals(3, result.successes)
        assertEquals(3, result.products)
        assertFalse(result.isEligible(requiredProducts))
    }

    @Test
    fun youtubeAndDiscordWithUdpQualifyWithoutTelegram() {
        val result = StrategyProbeResult.fromEndpointResults(
            listOf(
                "discord-core" to 30L,
                "youtube-core" to 40L,
                "telegram" to null,
            ),
            udpLatencyMs = 25L,
        )

        assertEquals(2, result.successes)
        assertEquals(2, result.products)
        assertEquals(95L, result.latencyMs)
        assertTrue(result.isEligible(requiredProducts))
    }

    @Test
    fun missingEitherMainProductCannotQualify() {
        val onlyYoutube = StrategyProbeResult.fromEndpointResults(
            listOf("youtube-core" to 20L, "youtube-media" to 30L),
            udpLatencyMs = 10L,
        )

        assertFalse(onlyYoutube.isEligible(requiredProducts))
        assertFalse(
            StrategyProbeResult.fromEndpointResults(emptyList(), udpLatencyMs = 5L)
                .isEligible(requiredProducts),
        )
    }

    @Test
    fun mediaEndpointsWinBeforeGenericEndpointCountAndLatency() {
        val mediaCapable = StrategyProbeResult.fromEndpointResults(
            listOf(
                "discord-core" to 200L,
                "youtube-core" to 200L,
                "discord-media" to 200L,
                "youtube-media" to 200L,
            ),
            udpLatencyMs = 100L,
        )
        val generic = StrategyProbeResult.fromEndpointResults(
            listOf(
                "discord-core" to 10L,
                "youtube-core" to 10L,
                "telegram" to 10L,
                "youtube-api" to 10L,
                "discord-gateway" to 10L,
            ),
            udpLatencyMs = 10L,
        )

        assertTrue(
            mediaCapable.isBetterThan(
                generic,
                mediaProducts,
                mediaReady = true,
                otherMediaReady = false,
            ),
        )
        assertFalse(
            generic.isBetterThan(
                mediaCapable,
                mediaProducts,
                mediaReady = false,
                otherMediaReady = true,
            ),
        )
    }

    @Test
    fun udpReadyProfileWinsTiesThenLowerLatencyWins() {
        val faster = StrategyProbeResult.fromEndpointResults(
            listOf("discord-core" to 10L, "youtube-core" to 10L),
            udpLatencyMs = 10L,
        )
        val slower = faster.copy(latencyMs = 90L)

        assertTrue(
            slower.isBetterThan(
                faster,
                mediaProducts,
                mediaReady = true,
                otherMediaReady = false,
            ),
        )
        assertTrue(
            faster.isBetterThan(
                slower,
                mediaProducts,
                mediaReady = true,
                otherMediaReady = true,
            ),
        )
        assertFalse(
            faster.isBetterThan(
                faster.copy(),
                mediaProducts,
                mediaReady = true,
                otherMediaReady = true,
            ),
        )
        assertTrue(
            faster.isBetterThan(
                null,
                mediaProducts,
                mediaReady = true,
                otherMediaReady = false,
            ),
        )
    }
}
