package fr.junade.taipo

import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.RadioGroup

/**
 * Écran « Paramètres du clavier ». Contient l'activation de la rangée de
 * chiffres (story 1.5) et le niveau de retour haptique (story 1.11) et la
 * hauteur du clavier (story 1.12).
 */
class KeyboardSettingsActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_settings)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        val preferences = KeyboardPreferences(this)

        findViewById<CompoundButton>(R.id.switch_number_row).apply {
            isChecked = preferences.isNumberRowEnabled
            setOnCheckedChangeListener { _, checked -> preferences.isNumberRowEnabled = checked }
        }

        val radioGroup = findViewById<RadioGroup>(R.id.radio_group_haptic)
        val radioIds = mapOf(
            HapticIntensity.OFF to R.id.radio_haptic_off,
            HapticIntensity.LIGHT to R.id.radio_haptic_light,
            HapticIntensity.MEDIUM to R.id.radio_haptic_medium,
            HapticIntensity.STRONG to R.id.radio_haptic_strong,
        )
        radioGroup.check(radioIds.getValue(preferences.hapticIntensity))
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = radioIds.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            preferences.hapticIntensity = selected
        }

        val heightGroup = findViewById<RadioGroup>(R.id.radio_group_height)
        val heightIds = mapOf(
            KeyboardHeight.COMPACT to R.id.radio_height_compact,
            KeyboardHeight.SMALL to R.id.radio_height_small,
            KeyboardHeight.NORMAL to R.id.radio_height_normal,
            KeyboardHeight.LARGE to R.id.radio_height_large,
            KeyboardHeight.EXTRA_LARGE to R.id.radio_height_extra_large,
        )
        heightGroup.check(heightIds.getValue(preferences.keyboardHeight))
        heightGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = heightIds.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            preferences.keyboardHeight = selected
        }
    }
}
