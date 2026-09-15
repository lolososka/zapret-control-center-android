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
        assertTrue(AppUpdateRepository.isNewerVersion("1.2.10", "1.2.9"))
        assertTrue(AppUpdateRepository.isNewerVersion("1.10.0", "1.9.99"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.2.9", "0.2.9"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.2.8", "0.2.9"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.3", "0.2.9"))
    }

    @Test
    fun acceptsOnlyKnownInstalledDebugSuffix() {
        assertTrue(AppUpdateRepository.isNewerVersion("0.3.0", "0.2.9-debug"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.2.9", "0.2.9-debug"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.3.0-debug", "0.2.9"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.3.0", "0.2.9-beta"))
        assertFalse(AppUpdateRepository.isNewerVersion("0.3.0", "0.2.9-debug-debug"))
    }

    @Test
    fun rejectsMalformedVersionsOnEitherSide() {
        val invalidVersions = listOf(
            "", "1", "1.2", "1.2.3.4", "+1.2.3", "-1.2.3", "1.-2.3",
            "1.2.-3", "01.2.3", "1.02.3", "1.2.03", " 1.2.3", "1.2.3 ",
            "1.2.3\n", "1.2.x", "2147483648.0.0", "1.2147483648.0", "1.2.2147483648",
        )
        invalidVersions.forEach { invalid ->
            assertFalse("Malformed candidate: $invalid", AppUpdateRepository.isNewerVersion(invalid, "0.2.9"))
            assertFalse("Malformed installed version: $invalid", AppUpdateRepository.isNewerVersion("2.0.0", invalid))
        }
    }

    @Test
    fun acceptsSameSignerAndForwardRotation() {
        val installed = certificates("old")
        assertTrue(trusted(certificates("old"), installed))
        assertTrue(trusted(certificates("new", history = setOf("old", "new")), installed))
    }

    @Test
    fun rejectsReverseRotationAndUnrelatedSigner() {
        val rotatedInstalled = certificates("new", history = setOf("old", "new"))
        assertFalse(trusted(certificates("old"), rotatedInstalled))
        assertFalse(trusted(certificates("unrelated"), certificates("old")))
    }

    @Test
    fun rejectsEmptyOrMalformedSingleSignerLineage() {
        assertFalse(trusted(certificates(), certificates("old")))
        assertFalse(trusted(certificates("new"), certificates()))
        assertFalse(trusted(certificates(history = setOf("old")), certificates("old")))
        assertFalse(trusted(certificates("new", history = setOf("old")), certificates("old")))
        assertFalse(trusted(certificates("new", history = emptySet()), certificates("old")))
    }

    @Test
    fun multipleSignersMustMatchExactlyRegardlessOfOrder() {
        val installed = certificates("a", "b")
        assertTrue(trusted(certificates("b", "a"), installed))
        assertFalse(trusted(certificates("a", "c"), installed))
        assertFalse(trusted(certificates("a"), installed))
        assertFalse(trusted(certificates("a", "b", "c"), installed))
        assertFalse(trusted(certificates("a", "c", history = setOf("a", "b", "c")), installed))
    }

    @Test
    fun legacyAndroidRequiresExactCurrentSigners() {
        val installed = certificates("old")
        assertTrue(trusted(certificates("old"), installed, supportsSigningHistory = false))
        assertFalse(trusted(certificates("new", history = setOf("old", "new")), installed, supportsSigningHistory = false))
        assertFalse(trusted(certificates(), certificates(), supportsSigningHistory = false))
    }

    private fun certificates(
        vararg current: String,
        history: Set<String> = current.toSet(),
        multiple: Boolean = current.size > 1,
    ) = AppUpdateRepository.SigningCertificates(current.toSet(), history, multiple)

    private fun trusted(
        candidate: AppUpdateRepository.SigningCertificates,
        installed: AppUpdateRepository.SigningCertificates,
        supportsSigningHistory: Boolean = true,
    ) = AppUpdateRepository.isTrustedSigningUpdate(candidate, installed, supportsSigningHistory)
}
