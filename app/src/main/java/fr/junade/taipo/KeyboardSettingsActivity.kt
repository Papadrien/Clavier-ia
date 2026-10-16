package fr.junade.taipo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Toast
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsPreferences
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
        setUpMultilingual(preferences)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

        findViewById<CompoundButton>(R.id.switch_number_row).apply {
            isChecked = preferences.isNumberRowEnabled
            setOnCheckedChangeListener { _, checked ->
                preferences.isNumberRowEnabled = checked
                Analytics.setting(this@KeyboardSettingsActivity, "number_row", checked)
            }
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
            Analytics.setting(this, "haptic", selected.storageKey)
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
            Analytics.setting(this, "keyboard_height", selected.storageKey)
        }

        findViewById<CompoundButton>(R.id.switch_habit_learning).apply {
            isChecked = preferences.isHabitLearningEnabled
            setOnCheckedChangeListener { _, checked ->
                preferences.isHabitLearningEnabled = checked
                Analytics.setting(this@KeyboardSettingsActivity, "habit_learning", checked)
            }
        }
        setUpAnalyticsSwitch()
        findViewById<Button>(R.id.button_habit_clear).setOnClickListener { confirmClearHabits() }
    }

    /**
     * Langue du clavier : liste déroulante « Automatique » puis une entrée par langue gérée ([KeyboardLanguage.entries],
     * nom dans la langue elle-même). La liste suit l'énumération : une langue ajoutée au code apparaît ici sans autre
     * modification.
     */
    private fun setUpLanguageChoice(preferences: KeyboardPreferences) {
        val spinner = findViewById<Spinner>(R.id.spinner_language)
        val choices: List<Pair<KeyboardLanguage?, String>> =
            listOf<Pair<KeyboardLanguage?, String>>(null to getString(R.string.keyboard_settings_language_auto)) +
                KeyboardLanguage.entries.map { it to it.displayName }
        spinner.adapter = ArrayAdapter(this, R.layout.spinner_item, choices.map { it.second })
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        spinner.setSelection(choices.indexOfFirst { it.first == preferences.keyboardLanguage }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = choices[position].first
                // Android notifie aussi la sélection initiale : on n'écrit que s'il y a un vrai changement.
                if (selected != preferences.keyboardLanguage) {
                    preferences.keyboardLanguage = selected
                    Analytics.setting(this@KeyboardSettingsActivity, "language", selected?.tag ?: "auto")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * Clavier multilingue : interrupteur + une case par langue gérée. Activé, l'appui long sur Entrée passe d'une langue
     * cochée à la suivante (au moins deux). À la première activation, la langue actuellement utilisée est pré-cochée.
     */
    private fun setUpMultilingual(preferences: KeyboardPreferences) {
        val switch = findViewById<CompoundButton>(R.id.switch_multilingual)
        val container = findViewById<LinearLayout>(R.id.layout_multilingual_languages)
        val minHint = findViewById<View>(R.id.text_multilingual_min)
        val selected = preferences.multilingualSelection.toMutableSet()
        val boxes = KeyboardLanguage.entries.map { language ->
            CheckBox(this).apply {
                text = language.displayName
                isChecked = language in selected
            }.also { container.addView(it) }
        }

        fun refresh() {
            val on = switch.isChecked
            container.visibility = if (on) View.VISIBLE else View.GONE
            minHint.visibility = if (on && !MultilingualChoice.isActive(selected.toList())) View.VISIBLE else View.GONE
        }
        boxes.forEachIndexed { index, box ->
            val language = KeyboardLanguage.entries[index]
            box.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(language) else selected.remove(language)
                preferences.multilingualSelection = selected.toList()
                refresh()
            }
        }
        switch.isChecked = preferences.isMultilingualEnabled
        switch.setOnCheckedChangeListener { _, checked ->
            preferences.isMultilingualEnabled = checked
            if (checked && selected.isEmpty()) {
                val current = KeyboardLanguageChoice.resolve(
                    preferences.keyboardLanguage,
                    resources.configuration.locales.let { list -> (0 until list.size()).map { list.get(it).toLanguageTag() } },
                )
                boxes[KeyboardLanguage.entries.indexOf(current)].isChecked = true // met aussi à jour la sélection enregistrée
            }
            refresh()
        }
        refresh()
    }

    /**
     * « Mesure d'usage anonyme » (Aptabase) : activée par défaut, désactivable ici. Désactivée, plus aucun événement n'est
     * créé (le SDK n'est plus appelé). Le passage à « activé » est le seul réglage de mesure qui est lui-même mesuré.
     */
    private fun setUpAnalyticsSwitch() {
        val analyticsPreferences = AnalyticsPreferences(this)
        findViewById<CompoundButton>(R.id.switch_analytics).apply {
            isChecked = analyticsPreferences.isEnabled
            setOnCheckedChangeListener { _, checked ->
                analyticsPreferences.isEnabled = checked
                if (checked) Analytics.setting(this@KeyboardSettingsActivity, "analytics", true)
            }
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
