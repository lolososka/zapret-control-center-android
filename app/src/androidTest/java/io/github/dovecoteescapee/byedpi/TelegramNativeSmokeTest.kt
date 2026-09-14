package io.github.dovecoteescapee.byedpi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dovecoteescapee.byedpi.core.TelegramWsProxy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelegramNativeSmokeTest {
    @Test
    fun telegramJniLoadsAndCallsNativeCore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TelegramWsProxy.configure(
            poolSize = 2,
            cacheDir = context.cacheDir.absolutePath,
            cloudflare = false,
            domain = "",
        )

        // CI uses the intentionally unsupported x86_64 stub. Reaching it
        // proves that tgwsproxy, the JNI bridge and its symbols all loaded.
        assertEquals(-1, TelegramWsProxy.start("127.0.0.1", 0, "", ""))
    }
}
