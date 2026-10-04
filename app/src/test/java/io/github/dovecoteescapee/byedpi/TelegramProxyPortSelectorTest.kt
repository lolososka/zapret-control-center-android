package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.TelegramProxyPortSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramProxyPortSelectorTest {
    @Test
    fun invalidPreferenceFallsBackToDefault() {
        assertEquals(TelegramProxyPortSelector.DEFAULT_PORT, TelegramProxyPortSelector.sanitize(0))
        assertEquals(TelegramProxyPortSelector.DEFAULT_PORT, TelegramProxyPortSelector.sanitize(65_536))
        assertEquals(443, TelegramProxyPortSelector.sanitize(443))
    }

    @Test
    fun candidatesAreValidUniqueAndBounded() {
        val candidates = TelegramProxyPortSelector.candidates(
            preferredPort = -1,
            availablePorts = listOf(0, 1443, 1443, 65_536) + (2000..2010),
        )

        assertEquals(TelegramProxyPortSelector.DEFAULT_PORT, candidates.first())
        assertEquals(candidates.size, candidates.distinct().size)
        assertTrue(candidates.all { it in 1..65_535 })
        assertTrue(candidates.size <= 8)
    }
}
