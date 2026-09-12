package io.github.dovecoteescapee.byedpi

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyUIPreferences
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrategyPreferencesTest {
    private val preferences by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("strategy-regression-test", Context.MODE_PRIVATE)
    }

    @Test
    fun legacyMessengerToggleCannotForceUdpForEveryProfile() {
        preferences.edit().clear().putBoolean("messaging_compat", true).commit()

        val config = ByeDpiProxyUIPreferences(preferences)

        assertFalse(config.desyncUdp)
        assertEquals(0, config.udpFakeCount)
    }

    @Test
    fun selectedProfileOwnsUdpSettings() {
        preferences.edit().clear().commit()

        StrategyProfiles.apply(preferences, StrategyProfiles.Profile.Messaging)
        assertTrue(ByeDpiProxyUIPreferences(preferences).desyncUdp)

        StrategyProfiles.apply(preferences, StrategyProfiles.Profile.Balanced)
        val balanced = ByeDpiProxyUIPreferences(preferences)
        assertFalse(balanced.desyncUdp)
        assertEquals(0, balanced.udpFakeCount)
    }
}
