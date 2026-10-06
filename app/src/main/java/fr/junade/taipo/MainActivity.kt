package fr.junade.taipo

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.ModelStatus
import fr.junade.taipo.model.VoiceModelFile
import fr.junade.taipo.model.VoiceModelPreferences

class MainActivity : Activity() {

    /** Vues d'une étape de l'accueil (lot UX 2). [action] : bouton de l'étape, absent pour l'étape d'essai. */
    private class StepViews(
        val number: Int,
        val card: View,
        val badge: TextView,
        val title: TextView,
        val hint: TextView,
        val action: View?,
    )

    private lateinit var step1: StepViews
    private lateinit var step2: StepViews
    private lateinit var step3: StepViews
    private lateinit var tryInput: EditText

    /** Opacité d'une étape pas encore accessible (dimens.xml, valeur décimale). */
    private val lockedAlpha: Float by lazy {
        val value = android.util.TypedValue()
        resources.getValue(R.dimen.taipo_step_locked_alpha, value, true)
        value.float
    }

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

        step1 = stepViews(1, R.id.step1_card, R.id.step1_badge, R.id.step1_title, R.id.step1_hint, R.id.button_enable)
        step2 = stepViews(2, R.id.step2_card, R.id.step2_badge, R.id.step2_title, R.id.step2_hint, R.id.button_select)
        step3 = stepViews(3, R.id.step3_card, R.id.step3_badge, R.id.step3_title, R.id.step3_hint, null)
        tryInput = findViewById(R.id.onboarding_try_input)

        findViewById<View>(R.id.button_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        // Sélecteur de méthode de saisie d'Android : il ne liste que les claviers activés (étape 1 faite).
        findViewById<View>(R.id.button_select).setOnClickListener {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        bindRow(R.id.button_keyboard_settings, R.string.button_keyboard_settings, R.string.home_row_keyboard_settings_subtitle, KeyboardSettingsActivity::class.java)
        bindRow(R.id.button_personal_dictionary, R.string.button_personal_dictionary, R.string.home_row_personal_dictionary_subtitle, PersonalDictionaryActivity::class.java)
        bindRow(R.id.button_model_settings, R.string.button_model_settings, R.string.home_row_model_settings_subtitle, ModelSettingsActivity::class.java)
        bindRow(R.id.button_voice_model_settings, R.string.button_voice_model_settings, R.string.home_row_voice_model_subtitle, VoiceModelSettingsActivity::class.java)
        bindRow(R.id.button_system_prompts, R.string.button_system_prompts, R.string.home_row_system_prompts_subtitle, SystemPromptsActivity::class.java)

        // Titres de groupe annoncés comme des titres par TalkBack (navigation par titres).
        ViewCompat.setAccessibilityHeading(findViewById(R.id.section_keyboard), true)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.section_ai), true)
    }

    // L'état des étapes vient d'Android (réglages système, sélecteur) : on le relit au retour sur l'écran et quand le
    // sélecteur (une fenêtre système) se ferme.
    override fun onResume() {
        super.onResume()
        renderOnboarding(readOnboardingState())
        renderModelStatuses()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) renderOnboarding(readOnboardingState())
    }

    /**
     * Lot UX 4 : pastille de statut sur les lignes « Modèle IA » et « Modèle vocal ». Relue à chaque retour sur
     * l'écran, puisque les fichiers se choisissent dans les sous-pages.
     */
    private fun renderModelStatuses() {
        val textPreferences = ModelPreferences(this)
        val activeModel = textPreferences.activeModel()
        val textStatus = ModelStatus.forTextModel(activeModel != null && textPreferences.savedUriFor(activeModel) != null)
        renderRowStatus(R.id.button_model_settings, textStatus, getString(statusLabel(textStatus)))

        val voiceFiles = VoiceModelFile.all().size
        val voiceProvided = VoiceModelPreferences(this).providedCount()
        val voiceStatus = ModelStatus.forVoiceModel(voiceProvided, voiceFiles)
        val voiceLabel = if (voiceStatus == ModelStatus.INCOMPLETE) {
            getString(R.string.home_status_incomplete, voiceProvided, voiceFiles)
        } else {
            getString(statusLabel(voiceStatus))
        }
        renderRowStatus(R.id.button_voice_model_settings, voiceStatus, voiceLabel)
    }

    @StringRes
    private fun statusLabel(status: ModelStatus): Int = when (status) {
        ModelStatus.READY -> R.string.home_status_ready
        else -> R.string.home_status_missing
    }

    private fun renderRowStatus(@IdRes rowId: Int, status: ModelStatus, label: String) {
        val badge = findViewById<View>(rowId).findViewById<TextView>(R.id.row_badge)
        val ready = status == ModelStatus.READY
        badge.text = label
        badge.setBackgroundResource(if (ready) R.drawable.bg_status_badge_ready else R.drawable.bg_status_badge_attention)
        badge.setTextColor(getColor(if (ready) R.color.text_primary else R.color.settings_warning_text))
        badge.visibility = View.VISIBLE
    }

    private fun readOnboardingState(): OnboardingState {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabledPackages = manager?.enabledInputMethodList.orEmpty().map { it.packageName }
        val defaultId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return OnboardingState.from(enabledPackages, defaultId, packageName)
    }

    /** Ligne de réglage de l'accueil (lot UX 3) : pose le titre et le sous-titre, ouvre [target] au toucher. */
    private fun bindRow(@IdRes id: Int, @StringRes title: Int, @StringRes subtitle: Int, target: Class<*>) {
        val row = findViewById<View>(id)
        row.findViewById<TextView>(R.id.row_title).setText(title)
        row.findViewById<TextView>(R.id.row_subtitle).setText(subtitle)
        row.setOnClickListener { startActivity(Intent(this, target)) }
    }

    private fun stepViews(number: Int, card: Int, badge: Int, title: Int, hint: Int, action: Int?) = StepViews(
        number = number,
        card = findViewById(card),
        badge = findViewById(badge),
        title = findViewById(title),
        hint = findViewById(hint),
        action = action?.let { findViewById<View>(it) },
    )

    private fun renderOnboarding(state: OnboardingState) {
        renderStep(step1, state.activate, R.string.onboarding_step1_title, R.string.onboarding_step1_title_done, R.string.onboarding_step1_hint, R.string.onboarding_step1_hint)
        renderStep(step2, state.choose, R.string.onboarding_step2_title, R.string.onboarding_step2_title_done, R.string.onboarding_step2_hint, R.string.onboarding_step2_hint_locked)
        renderStep(step3, state.tryIt, R.string.onboarding_step3_title, R.string.onboarding_step3_title, R.string.onboarding_step3_hint, R.string.onboarding_step3_hint_locked)
        tryInput.visibility = if (state.tryIt == StepStatus.CURRENT) View.VISIBLE else View.GONE
    }

    private fun renderStep(
        views: StepViews,
        status: StepStatus,
        @StringRes titleRes: Int,
        @StringRes titleDoneRes: Int,
        @StringRes hintRes: Int,
        @StringRes hintLockedRes: Int,
    ) {
        views.title.setText(if (status == StepStatus.DONE) titleDoneRes else titleRes)
        views.badge.text = if (status == StepStatus.DONE) "" else views.number.toString()
        views.badge.setBackgroundResource(
            when (status) {
                StepStatus.DONE -> R.drawable.bg_step_badge_done
                StepStatus.CURRENT -> R.drawable.bg_step_badge_current
                StepStatus.LOCKED -> R.drawable.bg_step_badge_locked
            },
        )
        // Étape faite : titre seul ; à faire : explication et bouton ; verrouillée : explication, carte atténuée.
        views.hint.visibility = if (status == StepStatus.DONE) View.GONE else View.VISIBLE
        views.hint.setText(if (status == StepStatus.LOCKED) hintLockedRes else hintRes)
        views.action?.visibility = if (status == StepStatus.CURRENT) View.VISIBLE else View.GONE
        views.card.alpha = if (status == StepStatus.LOCKED) lockedAlpha else 1f
    }
}
