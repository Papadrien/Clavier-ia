package fr.junade.taipo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsPreferences
import fr.junade.taipo.clipboard.ScreenshotFinder
import fr.junade.taipo.dictionary.CorrectionHabitsProvider

/**
 * Écran « Paramètres du clavier ». Contient l'activation de la rangée de
 * chiffres (story 1.5) et le niveau de retour haptique (story 1.11) et la
 * hauteur du clavier (story 1.12), ainsi que l'option « Apprendre de mes habitudes » et l'effacement des
 * habitudes de correction. Les langues (liste, disposition, dictionnaire) ont leur propre écran : [LanguagesActivity].
 */
class KeyboardSettingsActivity : Activity() {

    /** Interrupteur « captures d'écran » : remis à faux si la permission d'images est refusée. */
    private var screenshotSwitch: CompoundButton? = null

    /** Vrai pendant que l'interrupteur est changé par le code (refus de la permission) : le changement n'est ni enregistré ni mesuré. */
    private var settingScreenshotSwitch = false

    /** Lecteur d'aperçu du curseur de volume (préparé au premier toucher du curseur). */
    private val previewPlayer by lazy { KeySoundPlayer(applicationContext) }

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_settings)
        val preferences = KeyboardPreferences(this)
        setUpLanguagesRow()
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

        // « Uniquement dans les champs mot de passe » n'a plus d'objet quand la rangée est toujours affichée : l'option est grisée.
        val passwordOnlySwitch = findViewById<CompoundButton>(R.id.switch_number_row_passwords).apply {
            isChecked = preferences.isNumberRowInPasswordFieldsOnly
            isEnabled = !preferences.isNumberRowEnabled
            setOnCheckedChangeListener { _, checked ->
                preferences.isNumberRowInPasswordFieldsOnly = checked
                Analytics.setting(this@KeyboardSettingsActivity, "number_row_password_only", checked)
            }
        }
        findViewById<CompoundButton>(R.id.switch_number_row).apply {
            isChecked = preferences.isNumberRowEnabled
            setOnCheckedChangeListener { _, checked ->
                preferences.isNumberRowEnabled = checked
                passwordOnlySwitch.isEnabled = !checked
                Analytics.setting(this@KeyboardSettingsActivity, "number_row", checked)
            }
        }

        // Touches du bas : relues par le clavier à la prochaine ouverture d'un champ.
        findViewById<CompoundButton>(R.id.switch_show_emoji_key).apply {
            isChecked = preferences.isEmojiKeyShown
            setOnCheckedChangeListener { _, checked ->
                preferences.isEmojiKeyShown = checked
                Analytics.setting(this@KeyboardSettingsActivity, "show_emoji_key", checked)
            }
        }
        findViewById<CompoundButton>(R.id.switch_show_comma_key).apply {
            isChecked = preferences.isCommaKeyShown
            setOnCheckedChangeListener { _, checked ->
                preferences.isCommaKeyShown = checked
                Analytics.setting(this@KeyboardSettingsActivity, "show_comma_key", checked)
            }
        }
        findViewById<CompoundButton>(R.id.switch_show_period_key).apply {
            isChecked = preferences.isPeriodKeyShown
            setOnCheckedChangeListener { _, checked ->
                preferences.isPeriodKeyShown = checked
                Analytics.setting(this@KeyboardSettingsActivity, "show_period_key", checked)
            }
        }

        setUpScreenshotSuggestions(preferences)

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

        findViewById<CompoundButton>(R.id.switch_key_sound).apply {
            isChecked = preferences.isKeySoundEnabled
            setOnCheckedChangeListener { _, checked ->
                preferences.isKeySoundEnabled = checked
                Analytics.setting(this@KeyboardSettingsActivity, "key_sound", checked)
            }
        }

        findViewById<CompoundButton>(R.id.switch_bar_sound).apply {
            isChecked = preferences.isBarSoundEnabled
            setOnCheckedChangeListener { _, checked ->
                preferences.isBarSoundEnabled = checked
                Analytics.setting(this@KeyboardSettingsActivity, "bar_sound", checked)
            }
        }
        setUpSoundVolume(preferences)
        setUpBottomMargin(preferences)

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
        // Groupe « Précision de frappe » : réglages en cours de validation, visibles uniquement en debug.
        findViewById<View>(R.id.group_touch_precision).visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        findViewById<CompoundButton>(R.id.switch_adaptive_touch).apply {
            isChecked = preferences.isAdaptiveTouchEnabled
            setOnCheckedChangeListener { _, checked -> preferences.isAdaptiveTouchEnabled = checked }
        }
        findViewById<CompoundButton>(R.id.switch_touch_zones_debug).apply {
            // Calque des zones de frappe (debug) : fait partie du groupe masqué en release.
            isChecked = preferences.showTouchZones
            setOnCheckedChangeListener { _, checked -> preferences.showTouchZones = checked }
        }
        setUpAnalyticsSwitch()
        findViewById<Button>(R.id.button_habit_clear).setOnClickListener { confirmClearHabits() }
    }

    override fun onStart() {
        super.onStart()
        showLanguagesSummary() // l'écran « Langues » a pu changer la liste
    }

    override fun onDestroy() {
        previewPlayer.release()
        super.onDestroy()
    }

    /**
     * Curseur du volume des sons (0 à 100 %). Au relâchement, un tic de lettre est joué au nouveau volume pour
     * l'entendre ; le réglage est écrit au fil du déplacement et relu par le clavier à l'ouverture du prochain champ.
     */
    private fun setUpSoundVolume(preferences: KeyboardPreferences) {
        val seekBar = findViewById<SeekBar>(R.id.seek_sound_volume)
        val label = findViewById<TextView>(R.id.text_sound_volume)
        fun showValue(percent: Int) {
            label.text = getString(R.string.keyboard_settings_sound_volume_value, percent)
            seekBar.contentDescription = label.text
        }
        seekBar.max = KeySoundVolume.MAX_PERCENT
        seekBar.progress = preferences.keySoundVolumePercent
        showValue(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                showValue(progress)
                if (fromUser) preferences.keySoundVolumePercent = progress
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                previewPlayer.prepare() // synthèse des sons avant le relâchement
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                previewPlayer.volumePercent = bar.progress
                previewPlayer.play(KeySound.KEY)
                Analytics.setting(this@KeyboardSettingsActivity, "sound_volume", bar.progress)
            }
        })
    }

    /** Curseur « Marge inférieure » (0 à 100 dp) : écrit au fil du déplacement, relu par le clavier à l'ouverture du prochain champ. */
    private fun setUpBottomMargin(preferences: KeyboardPreferences) {
        val seekBar = findViewById<SeekBar>(R.id.seek_bottom_margin)
        val label = findViewById<TextView>(R.id.text_bottom_margin)
        fun showValue(dp: Int) {
            label.text = getString(R.string.keyboard_settings_bottom_margin_value, dp)
            seekBar.contentDescription = label.text
        }
        seekBar.max = KeyboardBottomMargin.MAX_DP
        seekBar.progress = preferences.bottomMarginDp
        showValue(seekBar.progress)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                showValue(progress)
                if (fromUser) preferences.bottomMarginDp = progress
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                Analytics.setting(this@KeyboardSettingsActivity, "bottom_margin", bar.progress)
            }
        })
    }

    /** Ligne « Langues » : ouvre l'écran des langues (liste, disposition et dictionnaire de chacune). */
    private fun setUpLanguagesRow() {
        findViewById<View>(R.id.button_languages).apply {
            findViewById<TextView>(R.id.row_title).setText(R.string.languages_title)
            setOnClickListener { startActivity(Intent(this@KeyboardSettingsActivity, LanguagesActivity::class.java)) }
        }
        showLanguagesSummary()
    }

    /** Sous-titre de la ligne « Langues » : les langues utilisées (ex. « Français, English »), relu à chaque retour sur l'écran. */
    private fun showLanguagesSummary() {
        val languages = KeyboardPreferences(this).languageSetup.inUse(automaticKeyboardLanguage())
        findViewById<View>(R.id.button_languages).findViewById<TextView>(R.id.row_subtitle).text =
            languages.joinToString(", ") { it.displayName }
    }

    /**
     * « Proposer les captures d'écran récentes » : l'activer demande la permission d'images (un clavier ne peut pas la demander
     * lui-même). Le réglage n'est enregistré qu'une fois la permission accordée ; il reste coché seulement tant qu'elle l'est.
     */
    private fun setUpScreenshotSuggestions(preferences: KeyboardPreferences) {
        val switch = findViewById<CompoundButton>(R.id.switch_screenshot_suggestions)
        screenshotSwitch = switch
        switch.isChecked = preferences.isScreenshotSuggestionEnabled && ScreenshotFinder.hasPermission(this)
        switch.setOnCheckedChangeListener { _, checked ->
            if (settingScreenshotSwitch) return@setOnCheckedChangeListener
            if (checked && !ScreenshotFinder.hasPermission(this)) {
                requestPermissions(arrayOf(ScreenshotFinder.permission), REQUEST_SCREENSHOT_PERMISSION)
            } else {
                preferences.isScreenshotSuggestionEnabled = checked
                Analytics.setting(this, "screenshot_suggestions", checked)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SCREENSHOT_PERMISSION) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        KeyboardPreferences(this).isScreenshotSuggestionEnabled = granted
        Analytics.setting(this, "screenshot_suggestions", granted)
        if (!granted) {
            settingScreenshotSwitch = true
            screenshotSwitch?.isChecked = false
            settingScreenshotSwitch = false
            Toast.makeText(this, R.string.keyboard_settings_screenshots_denied, Toast.LENGTH_LONG).show()
        }
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

    private companion object {
        /** Code de la demande de permission d'images (captures d'écran). */
        const val REQUEST_SCREENSHOT_PERMISSION = 4101
    }
}
