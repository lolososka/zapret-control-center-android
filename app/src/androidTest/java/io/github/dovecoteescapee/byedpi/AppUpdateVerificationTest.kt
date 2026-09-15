package io.github.dovecoteescapee.byedpi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dovecoteescapee.byedpi.core.AppUpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppUpdateVerificationTest {
    @Test
    fun installedApkCannotBeReinstalledAsAnUpdate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val error = assertThrows(IOException::class.java) {
            AppUpdateRepository.verifyApk(
                context,
                File(context.applicationInfo.sourceDir),
                requireNotNull(installed.versionName),
            )
        }
        assertEquals("APK is not newer", error.message)
    }

    @Test
    fun apkVersionMustMatchReleaseMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val error = assertThrows(IOException::class.java) {
            AppUpdateRepository.verifyApk(
                context,
                File(context.applicationInfo.sourceDir),
                "999.0.0",
            )
        }
        assertEquals("APK version mismatch", error.message)
    }

    @Test
    fun truncatedApkIsRejectedBeforeOpeningInstaller() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apk = File.createTempFile("truncated-update-", ".apk", context.cacheDir)
        try {
            apk.writeBytes(byteArrayOf(0x50, 0x4b))
            val error = assertThrows(IOException::class.java) {
                AppUpdateRepository.verifyApk(context, apk, "999.0.0")
            }
            assertEquals("Invalid APK file", error.message)
        } finally {
            apk.delete()
        }
    }
}
