package io.github.dovecoteescapee.byedpi.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.core.StrategyProfiles
import io.github.dovecoteescapee.byedpi.utility.getPreferences

class StrategyPickerActivity : AppCompatActivity() {
    private lateinit var autoCard: MaterialCardView
    private lateinit var balancedCard: MaterialCardView
    private lateinit var strongCard: MaterialCardView
    private var selected = StrategyProfiles.Profile.Balanced

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_picker)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.strategy_picker_title)

        autoCard = findViewById(R.id.strategy_auto_card)
        balancedCard = findViewById(R.id.strategy_balanced_card)
        strongCard = findViewById(R.id.strategy_strong_card)
        selected = StrategyProfiles.selected(getPreferences())
        select(selected)

        autoCard.setOnClickListener { select(StrategyProfiles.Profile.Auto) }
        balancedCard.setOnClickListener { select(StrategyProfiles.Profile.Balanced) }
        strongCard.setOnClickListener { select(StrategyProfiles.Profile.Strong) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.strategy_apply_button)
            .setOnClickListener {
                StrategyProfiles.apply(getPreferences(), selected)
                setResult(RESULT_OK)
                finish()
            }
    }

    private fun select(profile: StrategyProfiles.Profile) {
        selected = profile
        val selectedColor = ContextCompat.getColor(this, R.color.zapret_violet)
        val normalColor = ContextCompat.getColor(this, R.color.zapret_line)
        autoCard.strokeColor = if (profile == StrategyProfiles.Profile.Auto) selectedColor else normalColor
        balancedCard.strokeColor = if (profile == StrategyProfiles.Profile.Balanced) selectedColor else normalColor
        strongCard.strokeColor = if (profile == StrategyProfiles.Profile.Strong) selectedColor else normalColor
        autoCard.strokeWidth = if (profile == StrategyProfiles.Profile.Auto) 2 else 1
        balancedCard.strokeWidth = if (profile == StrategyProfiles.Profile.Balanced) 2 else 1
        strongCard.strokeWidth = if (profile == StrategyProfiles.Profile.Strong) 2 else 1
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
