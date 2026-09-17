package io.github.dovecoteescapee.byedpi.core

import android.content.Context
import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.R

object StrategyProfiles {
    const val KEY = "byedpi_strategy_profile"
    const val ACTIVE_KEY = "byedpi_strategy_active_profile"
    private const val CONFIG_VERSION_KEY = "byedpi_strategy_config_version"
    private const val CONFIG_VERSION = 1
    private const val DEFAULT_ID = "balanced"

    enum class Profile(
        val id: String,
        val title: Int,
        val method: String,
        val splitPosition: Int,
        val splitAtHost: Boolean,
        val tlsRecordSplit: Boolean,
        val tlsRecordSplitAtSni: Boolean,
        val fakeTtl: Int,
        val fakeOffset: Int,
        val desyncUdp: Boolean = false,
        val udpFakeCount: Int = 0,
    ) {
        Auto(
            "auto",
            R.string.strategy_auto,
            "disorder",
            1,
            false,
            true,
            true,
            8,
            0,
        ),
        Balanced(
            "balanced",
            R.string.strategy_balanced,
            "disorder",
            1,
            false,
            false,
            false,
            8,
            0,
            true,
            1,
        ),
        Messaging(
            "messaging",
            R.string.strategy_messaging,
            "disorder",
            1,
            false,
            true,
            true,
            8,
            0,
            true,
            1,
        ),
        Games(
            "games",
            R.string.strategy_games,
            "disorder",
            1,
            false,
            false,
            false,
            8,
            0,
            true,
            2,
        ),
        Strong(
            "strong",
            R.string.strategy_strong,
            "fake",
            -1,
            false,
            true,
            true,
            8,
            0,
            true,
            1,
        );

        val mediaReady: Boolean
            get() = desyncUdp && udpFakeCount > 0
    }

    fun selected(preferences: SharedPreferences): Profile =
        Profile.entries.firstOrNull {
            it.id == preferences.getString(KEY, DEFAULT_ID)
        } ?: Profile.Balanced

    fun active(preferences: SharedPreferences): Profile {
        val selected = selected(preferences)
        if (selected != Profile.Auto) return selected
        return Profile.entries.firstOrNull {
            it != Profile.Auto && it.id == preferences.getString(ACTIVE_KEY, DEFAULT_ID)
        } ?: Profile.Balanced
    }

    fun apply(preferences: SharedPreferences, profile: Profile) {
        val active = if (profile == Profile.Auto) active(preferences) else profile
        writeConfiguration(preferences, selected = profile, active = active)
    }

    /** Rewrites old presets once so upgrades receive the current UDP settings. */
    fun migrateIfNeeded(preferences: SharedPreferences) {
        if (preferences.getInt(CONFIG_VERSION_KEY, 0) >= CONFIG_VERSION) return
        if (preferences.getBoolean("byedpi_enable_cmd_settings", false)) {
            preferences.edit().putInt(CONFIG_VERSION_KEY, CONFIG_VERSION).apply()
            return
        }

        val selected = selected(preferences)
        val active = if (selected == Profile.Auto) active(preferences) else selected
        writeConfiguration(preferences, selected, active)
    }

    fun applyAutoCandidate(preferences: SharedPreferences, profile: Profile) {
        require(profile != Profile.Auto) { "Auto cannot be used as its own candidate" }
        writeConfiguration(preferences, selected = Profile.Auto, active = profile)
    }

    fun stageAutoCandidate(preferences: SharedPreferences, profile: Profile) {
        require(profile != Profile.Auto) { "Auto cannot be used as its own candidate" }
        writeActiveConfiguration(preferences.edit(), profile).apply()
    }

    private fun writeConfiguration(
        preferences: SharedPreferences,
        selected: Profile,
        active: Profile,
    ) {
        val editor = preferences.edit()
            .putString(KEY, selected.id)
            .putString(ACTIVE_KEY, active.id)
            .putInt(CONFIG_VERSION_KEY, CONFIG_VERSION)
        writeActiveConfiguration(editor, active).apply()
    }

    private fun writeActiveConfiguration(
        editor: SharedPreferences.Editor,
        active: Profile,
    ): SharedPreferences.Editor =
        editor
            .putBoolean("byedpi_enable_cmd_settings", false)
            .putString("byedpi_desync_method", active.method)
            .putString("byedpi_split_position", active.splitPosition.toString())
            .putBoolean("byedpi_split_at_host", active.splitAtHost)
            .putBoolean("byedpi_tlsrec_enabled", active.tlsRecordSplit)
            .putString("byedpi_tlsrec_position", "1")
            .putBoolean("byedpi_tlsrec_at_sni", active.tlsRecordSplitAtSni)
            .putString("byedpi_fake_ttl", active.fakeTtl.toString())
            .putString("byedpi_fake_offset", active.fakeOffset.toString())
            .putBoolean("byedpi_desync_http", true)
            .putBoolean("byedpi_desync_https", true)
            .putBoolean("byedpi_desync_udp", active.desyncUdp)
            .putString("byedpi_udp_fake_count", active.udpFakeCount.toString())
            .remove("messaging_compat")
            .remove("telegram_compat")

    fun title(context: Context, preferences: SharedPreferences): String {
        val selected = selected(preferences)
        return if (selected == Profile.Auto) {
            context.getString(
                R.string.strategy_auto_active,
                context.getString(active(preferences).title),
            )
        } else {
            context.getString(selected.title)
        }
    }

    fun diagnosticLabel(preferences: SharedPreferences): String {
        val selected = selected(preferences)
        return if (selected == Profile.Auto) {
            "auto · active=${active(preferences).id}"
        } else {
            selected.id
        }
    }
}
