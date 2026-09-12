package io.github.dovecoteescapee.byedpi.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores only the latest technical failure; no addresses, traffic or secrets. */
object ConnectionDiagnostics {
    private const val PREFS = "connection_diagnostics"
    private const val LAST_FAILURE = "last_failure"

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(LAST_FAILURE)
            .apply()
    }

    fun record(context: Context, component: String, error: Throwable) {
        val detail = error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }
        record(context, component, detail)
    }

    fun record(context: Context, component: String, detail: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        val value = "$timestamp · $component · ${detail.take(240)}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_FAILURE, value)
            .apply()
    }

    fun lastFailure(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_FAILURE, null)
}
