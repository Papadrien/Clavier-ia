package fr.junade.taipo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import fr.junade.taipo.dictionary.CorrectionHabitsProvider

/**
 * Écran « Paramètres du clavier ». Contient l'activation de la rangée de
 * chiffres (story 1.5) et le niveau de retour haptique (story 1.11) et la
 * hauteur du clavier (story 1.12), ainsi que l'option « Apprendre de mes habitudes » et l'effacement des
 * habitudes de correction.
 */
class KeyboardSettingsActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_settings)
        val preferences = KeyboardPreferences(this)
        setUpLanguageChoice(preferences)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

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

        findViewById<CompoundButton>(R.id.switch_habit_learning).apply {
            isChecked = preferences.isHabitLearningEnabled
            setOnCheckedChangeListener { _, checked -> preferences.isHabitLearningEnabled = checked }
        }
        findViewById<Button>(R.id.button_habit_clear).setOnClickListener { confirmClearHabits() }
    }

    /**
     * Langue du clavier : « Automatique » puis une ligne par langue gérée ([KeyboardLanguage.entries], nom dans la langue
     * elle-même). La liste suit l'énumération : une langue ajoutée au code apparaît ici sans autre modification.
     */
    private fun setUpLanguageChoice(preferences: KeyboardPreferences) {
        val group = findViewById<RadioGroup>(R.id.radio_group_language)
        val choices: List<Pair<KeyboardLanguage?, String>> =
            listOf<Pair<KeyboardLanguage?, String>>(null to getString(R.string.keyboard_settings_language_auto)) +
                KeyboardLanguage.entries.map { it to it.displayName }
        val ids = choices.map { (language, label) ->
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                layoutParams = RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
            }
            group.addView(button)
            language to button.id
        }
        group.check(ids.first { it.first == preferences.keyboardLanguage }.second)
        group.setOnCheckedChangeListener { _, checkedId ->
            val selected = ids.firstOrNull { it.second == checkedId } ?: return@setOnCheckedChangeListener
            preferences.keyboardLanguage = selected.first
        }
    }

    private fun confirmClearHabits() {
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setTitle(R.string.keyboard_settings_habits_clear_title)
            .setMessage(R.string.keyboard_settings_habits_clear_message)
            .setPositiveButton(R.string.keyboard_settings_habits_clear_confirm) { _, _ ->
                CorrectionHabitsProvider.repository(this).clear()
                Toast.makeText(this, R.string.keyboard_settings_habits_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.keyboard_settings_habits_clear_cancel, null)
            .show()
    }
}
