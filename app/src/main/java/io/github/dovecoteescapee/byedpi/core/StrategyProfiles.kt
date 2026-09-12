package io.github.dovecoteescapee.byedpi.core

import android.content.Context
import android.content.SharedPreferences
import io.github.dovecoteescapee.byedpi.R

/** Small, phone-friendly presets. The detailed editor remains available for manual tuning. */
object StrategyProfiles {
    const val KEY = "byedpi_strategy_profile"
    private const val DEFAULT_ID = "balanced"

    enum class Profile(
        val id: String,
        val title: Int,
        val summary: Int,
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
            R.string.strategy_auto_summary,
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
            R.string.strategy_balanced_summary,
            "disorder",
            1,
            false,
            false,
            false,
            8,
            0,
        ),
        Messaging(
            "messaging",
            R.string.strategy_messaging,
            R.string.strategy_messaging_summary,
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
            R.string.strategy_games_summary,
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
            R.string.strategy_strong_summary,
            "fake",
            -1,
            false,
            true,
            true,
            8,
            0,
        ),
    }

    fun selected(preferences: SharedPreferences): Profile =
        Profile.entries.firstOrNull {
            it.id == preferences.getString(KEY, DEFAULT_ID)
        } ?: Profile.Balanced

    fun apply(preferences: SharedPreferences, profile: Profile) {
        preferences.edit()
            .putString(KEY, profile.id)
            .putBoolean("byedpi_enable_cmd_settings", false)
            .putString("byedpi_desync_method", profile.method)
            .putString("byedpi_split_position", profile.splitPosition.toString())
            .putBoolean("byedpi_split_at_host", profile.splitAtHost)
            .putBoolean("byedpi_tlsrec_enabled", profile.tlsRecordSplit)
            .putString("byedpi_tlsrec_position", "1")
            .putBoolean("byedpi_tlsrec_at_sni", profile.tlsRecordSplitAtSni)
            .putString("byedpi_fake_ttl", profile.fakeTtl.toString())
            .putString("byedpi_fake_offset", profile.fakeOffset.toString())
            .putBoolean("byedpi_desync_http", true)
            .putBoolean("byedpi_desync_https", true)
            .putBoolean("byedpi_desync_udp", profile.desyncUdp)
            .putString("byedpi_udp_fake_count", profile.udpFakeCount.toString())
            .remove("messaging_compat")
            .remove("telegram_compat")
            .apply()
    }

    fun title(context: Context, preferences: SharedPreferences): String =
        context.getString(selected(preferences).title)
}
