package io.github.dovecoteescapee.byedpi

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.activities.SettingsActivity
import io.github.dovecoteescapee.byedpi.activities.StrategyPickerActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainScreenSmokeTest {
    @Test
    fun mainAndSettingsOpenWithReachableControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val dial = activity.findViewById<View>(R.id.route_dial)
                val oldStart = activity.findViewById<View>(R.id.status_button)
                val strategy = activity.findViewById<TextView>(R.id.strategy_badge)
                val settings = activity.findViewById<View>(R.id.settings_button)
                assertTrue("Dial action is not clickable", dial.isClickable && dial.isShown)
                assertTrue("Legacy start button is still visible", oldStart.visibility != View.VISIBLE)
                assertTrue("Strategy badge has no label", strategy.text.isNotBlank())
                assertTrue("Settings action is clipped", settings.isShown && settings.width > 0)
            }
            screenshot("main.png")
        }
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use {
            instrumentation.waitForIdleSync()
            screenshot("settings.png")
        }
        ActivityScenario.launch<StrategyPickerActivity>(
            Intent(context, StrategyPickerActivity::class.java)
        ).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val apply = activity.findViewById<TextView>(R.id.strategy_apply_button)
                assertTrue("Strategy picker action has no label", apply.text.isNotBlank())
                assertTrue("Strategy picker action is disabled", apply.isEnabled)
            }
            screenshot("strategy.png")
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "smoke")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, name).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
