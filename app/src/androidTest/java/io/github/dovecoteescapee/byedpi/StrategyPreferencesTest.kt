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
        assertTrue(balanced.desyncUdp)
        assertEquals(1, balanced.udpFakeCount)
    }

    @Test
    fun maximumProfileCoversVideoAndVoiceUdp() {
        preferences.edit().clear().commit()

        StrategyProfiles.apply(preferences, StrategyProfiles.Profile.Strong)
        val strong = ByeDpiProxyUIPreferences(preferences)

        assertTrue(strong.desyncUdp)
        assertEquals(1, strong.udpFakeCount)
        assertTrue(StrategyProfiles.Profile.Strong.mediaReady)
    }

    @Test
    fun oldBalancedPresetIsMigratedToMediaSettings() {
        preferences.edit()
            .clear()
            .putString(StrategyProfiles.KEY, StrategyProfiles.Profile.Balanced.id)
            .putBoolean("byedpi_desync_udp", false)
            .putString("byedpi_udp_fake_count", "0")
            .commit()

        StrategyProfiles.migrateIfNeeded(preferences)

        val migrated = ByeDpiProxyUIPreferences(preferences)
        assertTrue(migrated.desyncUdp)
        assertEquals(1, migrated.udpFakeCount)
    }

    @Test
    fun autoSelectionSurvivesCandidateChanges() {
        preferences.edit().clear().commit()

        StrategyProfiles.apply(preferences, StrategyProfiles.Profile.Auto)
        StrategyProfiles.applyAutoCandidate(preferences, StrategyProfiles.Profile.Strong)

        assertEquals(StrategyProfiles.Profile.Auto, StrategyProfiles.selected(preferences))
        assertEquals(StrategyProfiles.Profile.Strong, StrategyProfiles.active(preferences))
        val active = ByeDpiProxyUIPreferences(preferences)
        assertEquals("fake", active.desyncMethod.name.lowercase())
        assertTrue(active.tlsRecordSplit)
    }

    @Test
    fun invalidAutoCandidateFallsBackToBalanced() {
        preferences.edit()
            .clear()
            .putString(StrategyProfiles.KEY, StrategyProfiles.Profile.Auto.id)
            .putString(StrategyProfiles.ACTIVE_KEY, "missing")
            .commit()

        assertEquals(StrategyProfiles.Profile.Auto, StrategyProfiles.selected(preferences))
        assertEquals(StrategyProfiles.Profile.Balanced, StrategyProfiles.active(preferences))
    }

    @Test
    fun trialConfigurationDoesNotCommitUnverifiedCandidate() {
        preferences.edit().clear().commit()
        StrategyProfiles.apply(preferences, StrategyProfiles.Profile.Auto)

        StrategyProfiles.stageAutoCandidate(preferences, StrategyProfiles.Profile.Strong)

        assertEquals(StrategyProfiles.Profile.Auto, StrategyProfiles.selected(preferences))
        assertEquals(StrategyProfiles.Profile.Balanced, StrategyProfiles.active(preferences))
        assertEquals("fake", ByeDpiProxyUIPreferences(preferences).desyncMethod.name.lowercase())
    }
}
