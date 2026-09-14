package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.core.AppUpdateRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun comparesStableVersionsNumerically() {
        assertTrue(AppUpdateRepository.isNewerVersion("0.3.0", "0.2.9"))
        assertTrue(AppUpdateRepository.isNewerVersion("1.0.0", "0.99.99"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.2.9", "0.2.9"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.2.8", "0.2.9"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.3", "0.2.9"))
    }
}
