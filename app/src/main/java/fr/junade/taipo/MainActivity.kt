package fr.junade.taipo

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsPreferences
import fr.junade.taipo.diagnostic.MemoryDiagnosticActivity
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.ModelRecommendation
import fr.junade.taipo.model.ModelStatus
import fr.junade.taipo.model.VoiceModelFile
import fr.junade.taipo.model.VoiceModelPreferences
import fr.junade.taipo.premium.PremiumBilling
import fr.junade.taipo.review.InAppReview

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
    private lateinit var step4: StepViews
    private lateinit var step5: StepViews
    private lateinit var permissionsStep: OnboardingPermissionsStep
    private lateinit var tryInput: EditText

    /** Relit l'achat du pass chez Google Play à chaque retour sur l'accueil (achat fait ailleurs, remboursement). */
    private lateinit var billing: PremiumBilling

    /** Opacité d'une étape pas encore accessible (dimens.xml, valeur décimale). */
    private val lockedAlpha: Float by lazy {
        val value = android.util.TypedValue()
        resources.getValue(R.dimen.taipo_step_locked_alpha, value, true)
        value.float
    }

    // Écrans de l'application : même thème que le clavier (page « Apparence »), voir KeyboardTheme.wrap.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splashscreen (logo Junadé) : à appeler avant super.onCreate(). Remplace le thème de lancement par AppTheme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        // Mesure du nombre de nouveaux utilisateurs : événement envoyé une seule fois par installation.
        Analytics.firstOpen(this) // sans effet tant que l'utilisateur n'a pas accepté la mesure d'usage (audit PRIV-01)

        step1 = stepViews(1, R.id.step1_card, R.id.step1_badge, R.id.step1_title, R.id.step1_hint, R.id.button_enable)
        step2 = stepViews(2, R.id.step2_card, R.id.step2_badge, R.id.step2_title, R.id.step2_hint, R.id.button_select)
        step3 = stepViews(3, R.id.step3_card, R.id.step3_badge, R.id.step3_title, R.id.step3_hint, null)
        step4 = stepViews(4, R.id.step4_card, R.id.step4_badge, R.id.step4_title, R.id.step4_hint, R.id.button_activate_ai)
        step5 = stepViews(5, R.id.step5_card, R.id.step5_badge, R.id.step5_title, R.id.step5_hint, R.id.button_allow_permissions)
        val allowPermissions = findViewById<View>(R.id.button_allow_permissions)
        permissionsStep = OnboardingPermissionsStep(this, homePrefs(), findViewById(R.id.permissions_list), allowPermissions)
        allowPermissions.setOnClickListener { permissionsStep.request() }
        tryInput = findViewById(R.id.onboarding_try_input)
        // Étape 3 : faite dès que quelques mots ont été tapés dans le champ d'essai (retenu, l'étape ne revient pas).
        tryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s == null || homePrefs().getBoolean(KEY_TRIED, false) || !OnboardingState.triedWith(s)) return
                homePrefs().edit().putBoolean(KEY_TRIED, true).apply()
                renderOnboarding(readOnboardingState())
            }
        })
        findViewById<View>(R.id.button_activate_ai).setOnClickListener {
            startActivity(Intent(this, ModelDownloadActivity::class.java))
        }

        findViewById<View>(R.id.button_enable).setOnClickListener {
            watchKeyboardEnabled()
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        // Sélecteur de méthode de saisie d'Android : il ne liste que les claviers activés (étape 1 faite).
        findViewById<View>(R.id.button_select).setOnClickListener {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        bindRows()
        setUpPremium()
        setUpAnalyticsSwitch()

        // Titres de groupe annoncés comme des titres par TalkBack (navigation par titres).
        ViewCompat.setAccessibilityHeading(findViewById(R.id.section_keyboard), true)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.section_ai), true)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.section_data), true)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.section_about), true)

        // Demande d'avis : un « lancement » est une nouvelle instance de l'écran d'accueil (pas une rotation ni une
        // recréation après arrêt du processus, qui arrivent avec un état sauvegardé).
        if (savedInstanceState == null) InAppReview.onAppLaunched(this)
    }

    /** Lignes de l'accueil : titre, sous-titre et écran ouvert. */
    private fun bindRows() {
        bindRow(
            R.id.button_keyboard_settings,
            R.string.button_keyboard_settings,
            R.string.home_row_keyboard_settings_subtitle,
            KeyboardSettingsActivity::class.java,
        )
        bindRow(R.id.button_theme, R.string.button_theme, R.string.home_row_theme_subtitle, ThemeActivity::class.java)
        bindRow(R.id.button_home_languages, R.string.languages_title, R.string.home_row_languages_subtitle, LanguagesActivity::class.java)
        bindRow(
            R.id.button_personal_dictionary,
            R.string.button_personal_dictionary,
            R.string.home_row_personal_dictionary_subtitle,
            PersonalDictionaryActivity::class.java,
        )
        bindRow(R.id.button_model_download, R.string.button_model_download, R.string.home_row_model_download_subtitle, ModelDownloadActivity::class.java)
        bindRow(R.id.button_model_settings, R.string.button_model_settings, R.string.home_row_model_settings_subtitle, ModelSettingsActivity::class.java)
        // Stories 8.12 et 8.15 : le chargement des modèles à la main est réservé au debug (la variante benchmark n'est pas
        // debug) ; ses deux lignes sont dans la section « Debug », en bas de l'accueil.
        findViewById<View>(R.id.debug_local_models).visibility =
            if (LocalModelAccess.isVisible(BuildConfig.DEBUG)) View.VISIBLE else View.GONE
        // Story 8.15 : le modèle vocal se télécharge depuis son propre écran « Modèle vocal », distinct de « Modèle IA ».
        bindRow(
            R.id.button_voice_model_settings,
            R.string.button_voice_model_settings,
            R.string.home_row_voice_model_subtitle,
            VoiceModelDownloadActivity::class.java,
        )
        bindRow(
            R.id.button_voice_model_local,
            R.string.button_voice_model_local,
            R.string.home_row_voice_model_local_subtitle,
            VoiceModelSettingsActivity::class.java,
        )
        bindRow(R.id.button_system_prompts, R.string.button_system_prompts, R.string.home_row_system_prompts_subtitle, SystemPromptsActivity::class.java)
        // Pass premium : sans lui, la ligne porte un cadenas et ouvre l'écran du pass (lecture et modification des prompts réservées).
        findViewById<View>(R.id.button_system_prompts).setOnClickListener {
            val target = if (PremiumPass.isActive) {
                Intent(this, SystemPromptsActivity::class.java)
            } else {
                PremiumActivity.intent(this, PremiumActivity.SOURCE_PROMPTS)
            }
            startActivity(target)
        }
        bindRow(R.id.button_custom_model, R.string.custom_model_title, R.string.home_row_custom_model_subtitle, CustomModelActivity::class.java)
        // Pack premium (toujours ouvert en debug) : sans lui, la ligne porte un cadenas et ouvre l'écran du pack.
        findViewById<View>(R.id.button_custom_model).setOnClickListener {
            val target = if (CustomModelAccess.allowed) {
                Intent(this, CustomModelActivity::class.java)
            } else {
                PremiumActivity.intent(this, PremiumActivity.SOURCE_CUSTOM_MODEL)
            }
            startActivity(target)
        }
        bindRow(R.id.button_backup, R.string.button_backup, R.string.home_row_backup_subtitle, BackupActivity::class.java)
        bindRow(R.id.button_terms, R.string.button_terms, R.string.home_row_terms_subtitle, TermsActivity::class.java)
        bindRow(R.id.button_licenses, R.string.button_licenses, R.string.home_row_licenses_subtitle, LicensesActivity::class.java)
        bindRow(R.id.button_privacy, R.string.button_privacy, R.string.home_row_privacy_subtitle, PrivacyActivity::class.java)
    }

    // L'état des étapes vient d'Android (réglages système, sélecteur) : on le relit au retour sur l'écran et quand le
    // sélecteur (une fenêtre système) se ferme.
    override fun onResume() {
        super.onResume()
        renderOnboarding(readOnboardingState())
        renderModelStatuses()
        renderThemeRow()
        renderLanguagesRow()
        renderPremium()
        billing.refresh()
    }

    override fun onDestroy() {
        stopWatchingKeyboardEnabled()
        billing.close()
        super.onDestroy()
    }

    // Audit UX-02 : l'utilisateur active Taipo dans les réglages système ; dès que c'est fait, on le ramène ici et on ouvre le
    // sélecteur de clavier (étape 2), au lieu de le laisser chercher le chemin du retour.
    private var enabledObserver: ContentObserver? = null
    private var pickerOnReturn = false

    private fun watchKeyboardEnabled() {
        if (enabledObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!readOnboardingState().enabled) return
                stopWatchingKeyboardEnabled()
                pickerOnReturn = true
                startActivity(
                    Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer)
        enabledObserver = observer
    }

    private fun stopWatchingKeyboardEnabled() {
        enabledObserver?.let { contentResolver.unregisterContentObserver(it) }
        enabledObserver = null
    }

    /** Pass premium : carte d'offre, ligne de « À propos » et, en build debug, bouton de forçage tout en bas. */
    private fun setUpPremium() {
        billing = PremiumBilling(
            this,
            object : PremiumBilling.Listener {
                override fun onStateChanged(state: PremiumBilling.State) = renderPremium()
            },
        )
        val row = findViewById<View>(R.id.button_premium)
        row.findViewById<TextView>(R.id.row_title).setText(R.string.premium_title)
        row.setOnClickListener { startActivity(PremiumActivity.intent(this, PremiumActivity.SOURCE_ABOUT)) }
        findViewById<View>(R.id.premium_card).isSelected = true // contour violet de la carte
        findViewById<View>(R.id.button_premium_card).setOnClickListener {
            startActivity(PremiumActivity.intent(this, PremiumActivity.SOURCE_HOME))
        }
        findViewById<View>(R.id.button_premium_card_close).setOnClickListener {
            homePrefs().edit().putBoolean(KEY_PREMIUM_CARD_DISMISSED, true).apply()
            renderPremium()
        }
        findViewById<View>(R.id.debug_section).visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        findViewById<View>(R.id.button_debug_diagnostic).setOnClickListener {
            startActivity(Intent(this, MemoryDiagnosticActivity::class.java))
        }
        findViewById<View>(R.id.button_debug_reset_tips).setOnClickListener {
            GestureTips.resetAll(this)
            showScreenMessage(R.string.home_debug_reset_tips_done)
        }
        findViewById<View>(R.id.button_debug_premium).setOnClickListener {
            val states = PremiumPass.DebugOverride.entries
            PremiumPass.setDebugOverride(this, states[(PremiumPass.debugOverride.ordinal + 1) % states.size])
            renderPremium()
        }
    }

    private fun renderPremium() {
        val active = PremiumPass.isActive
        val onboarded = (lastOnboarding ?: readOnboardingState()).let { it.enabled && it.selected }
        val dismissed = homePrefs().getBoolean(KEY_PREMIUM_CARD_DISMISSED, false)
        findViewById<View>(R.id.premium_card).visibility = if (onboarded && !active && !dismissed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.button_premium).findViewById<TextView>(R.id.row_subtitle).setText(
            if (active) R.string.home_row_premium_subtitle_active else R.string.home_row_premium_subtitle_offer,
        )
        // Ligne « Prompts système » : cadenas à la place du chevron sans pass, annoncé par TalkBack.
        val prompts = findViewById<View>(R.id.button_system_prompts)
        prompts.findViewById<ImageView>(R.id.row_chevron)
            .setImageResource(if (active) R.drawable.ic_chevron_right else R.drawable.ic_lock)
        prompts.contentDescription = if (active) {
            null
        } else {
            getString(R.string.premium_locked_item, getString(R.string.button_system_prompts))
        }
        // Ligne « Modèle personnalisé » : même cadenas que les prompts système.
        val customModel = findViewById<View>(R.id.button_custom_model)
        val customAllowed = CustomModelAccess.allowed
        customModel.findViewById<ImageView>(R.id.row_chevron)
            .setImageResource(if (customAllowed) R.drawable.ic_chevron_right else R.drawable.ic_lock)
        customModel.contentDescription = if (customAllowed) null else getString(R.string.premium_locked_item, getString(R.string.custom_model_title))
        if (BuildConfig.DEBUG) {
            val bought = getString(if (PremiumPass.isPurchased) R.string.home_debug_premium_bought else R.string.home_debug_premium_not_bought)
            val state = when (PremiumPass.debugOverride) {
                PremiumPass.DebugOverride.NONE -> getString(R.string.home_debug_premium_none, bought)
                PremiumPass.DebugOverride.ACTIVE -> getString(R.string.home_debug_premium_active)
                PremiumPass.DebugOverride.INACTIVE -> getString(R.string.home_debug_premium_inactive)
            }
            findViewById<TextView>(R.id.button_debug_premium).text = getString(R.string.home_debug_premium, state)
        }
    }

    private fun homePrefs() = getSharedPreferences(HOME_PREFS, MODE_PRIVATE)

    /** « Mes données » : partage des données d'utilisation anonymes (Aptabase), déplacé ici depuis les paramètres du clavier. */
    private fun setUpAnalyticsSwitch() {
        val analyticsPreferences = AnalyticsPreferences(this)
        findViewById<CompoundButton>(R.id.switch_analytics).apply {
            isChecked = analyticsPreferences.isEnabled
            setOnCheckedChangeListener { _, checked ->
                analyticsPreferences.isEnabled = checked
                renderAnalyticsConsent()
                if (checked) onAnalyticsAccepted()
            }
        }
        findViewById<View>(R.id.button_analytics_accept).setOnClickListener {
            analyticsPreferences.isEnabled = true
            findViewById<CompoundButton>(R.id.switch_analytics).isChecked = true // l'écouteur fait le reste
            renderAnalyticsConsent()
        }
        findViewById<View>(R.id.button_analytics_refuse).setOnClickListener {
            analyticsPreferences.isEnabled = false
            renderAnalyticsConsent()
        }
        renderAnalyticsConsent()
    }

    /** Audit PRIV-01 : la carte d'accord reste en tête de l'accueil tant que l'utilisateur n'a pas choisi. */
    private fun renderAnalyticsConsent() {
        findViewById<View>(R.id.analytics_consent_card).visibility =
            if (AnalyticsPreferences(this).consentAnswered) View.GONE else View.VISIBLE
    }

    private fun onAnalyticsAccepted() {
        Analytics.firstOpen(this) // n'est envoyé qu'une fois, et seulement après l'accord
        Analytics.setting(this, "analytics", true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pickerOnReturn) {
            pickerOnReturn = false
            if (!readOnboardingState().selected) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
        if (hasFocus) {
            renderOnboarding(readOnboardingState())
            renderPremium() // la carte du pass n'apparaît qu'une fois Taipo choisi (sélecteur qui vient de se fermer)
        }
    }

    /** Ligne « Apparence » : thème et couleur choisis (« Sombre · Lagon »), relus au retour de la page (le choix s'y fait). */
    private fun renderThemeRow() {
        val preferences = KeyboardPreferences(this)
        val palette = AccentPalette.effective(preferences.accentPalette, PremiumPass.isActive)
        findViewById<View>(R.id.button_theme).findViewById<TextView>(R.id.row_subtitle).text =
            getString(R.string.home_row_theme_subtitle, getString(preferences.themeChoice.labelRes()), getString(palette.labelRes()))
    }

    /** Ligne « Langues » : les langues en service (« Français, English »), comme dans les paramètres du clavier. */
    private fun renderLanguagesRow() {
        val languages = KeyboardPreferences(this).languageSetup.inUse(automaticKeyboardLanguage())
        findViewById<View>(R.id.button_home_languages).findViewById<TextView>(R.id.row_subtitle).text =
            languages.joinToString(", ") { it.displayName }
    }

    /**
     * Lot UX 4 : pastille de statut sur les lignes « Modèle IA » et « Modèle vocal ». Relue à chaque retour sur
     * l'écran, puisque les fichiers se choisissent dans les sous-pages.
     */
    private fun renderModelStatuses() {
        // Ligne « Modèle IA » (téléchargement, épopée 8) : statut et sous-titre du modèle actif, qu'il soit
        // téléchargé ou fourni à la main (écran « Modèle IA local »).
        val textPreferences = ModelPreferences(this)
        val activeModel = textPreferences.activeModel()
        val activeInstalled = activeModel != null && textPreferences.isInstalled(activeModel)
        // Story 8.14 : modèle actif utilisable mais dont le fichier a une nouvelle version au catalogue.
        val updateAvailable = activeInstalled && textPreferences.updateAvailable(activeModel)
        val textStatus = if (updateAvailable) ModelStatus.INCOMPLETE else ModelStatus.forTextModel(activeInstalled)
        val textLabel = getString(
            when {
                updateAvailable -> R.string.home_status_update
                textStatus == ModelStatus.READY -> R.string.home_status_ready
                else -> R.string.home_status_to_download
            },
        )
        renderRowStatus(R.id.button_model_download, textStatus, textLabel)
        findViewById<View>(R.id.button_model_download).findViewById<TextView>(R.id.row_subtitle).text =
            if (activeModel != null && activeInstalled) {
                getString(R.string.model_download_technical_line, activeModel.displayName(this), activeModel.technicalName)
            } else {
                getString(R.string.home_row_model_download_subtitle)
            }

        val voicePreferences = VoiceModelPreferences(this)
        val voiceFiles = VoiceModelFile.all().size
        val voiceProvided = voicePreferences.providedCount()
        val voiceStatus = ModelStatus.forVoiceModel(voiceProvided, voiceFiles)
        // Story 8.14 appliquée au modèle vocal : un fichier téléchargé a une nouvelle version au catalogue.
        val voiceUpdate = voiceStatus == ModelStatus.READY && voicePreferences.updateAvailable()
        val voiceLabel = when {
            voiceUpdate -> getString(R.string.home_status_update)
            voiceStatus == ModelStatus.INCOMPLETE -> getString(R.string.home_status_incomplete, voiceProvided, voiceFiles)
            voiceStatus == ModelStatus.MISSING -> getString(R.string.home_status_to_download)
            else -> getString(statusLabel(voiceStatus))
        }
        renderRowStatus(
            R.id.button_voice_model_settings,
            if (voiceUpdate) ModelStatus.INCOMPLETE else voiceStatus,
            voiceLabel,
        )
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
        badge.setTextColor(getColor(if (ready) R.color.text_on_accent else R.color.settings_warning_text)) // blanc sur le violet
        badge.visibility = View.VISIBLE
    }

    private fun readOnboardingState(): OnboardingState {
        val manager = getSystemService(InputMethodManager::class.java)
        val enabledPackages = manager?.enabledInputMethodList.orEmpty().map { it.packageName }
        val defaultId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val models = ModelPreferences(this)
        val aiReady = models.activeModel()?.let(models::isInstalled) == true
        val permissionsDone = permissionsStep.isDone()
        return OnboardingState.from(
            enabledPackages,
            defaultId,
            packageName,
            tried = homePrefs().getBoolean(KEY_TRIED, false),
            aiReady = aiReady,
            permissionsDone = permissionsDone,
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (permissionsStep.onRequestResult(requestCode)) renderOnboarding(readOnboardingState())
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

    /** Dernier état lu de l'installation : la carte du pass s'en sert sans relire Android (lecture qui interroge le système). */
    private var lastOnboarding: OnboardingState? = null

    private fun renderOnboarding(state: OnboardingState) {
        lastOnboarding = state
        renderStep(
            step1,
            state.activate,
            R.string.onboarding_step1_title,
            R.string.onboarding_step1_title_done,
            R.string.onboarding_step1_hint,
            R.string.onboarding_step1_hint,
        )
        renderStep(
            step2,
            state.choose,
            R.string.onboarding_step2_title,
            R.string.onboarding_step2_title_done,
            R.string.onboarding_step2_hint,
            R.string.onboarding_step2_hint_locked,
        )
        renderStep(
            step3,
            state.tryIt,
            R.string.onboarding_step3_title,
            R.string.onboarding_step3_title_done,
            R.string.onboarding_step3_hint,
            R.string.onboarding_step3_hint_locked,
        )
        renderStep(
            step4,
            state.activateAi,
            R.string.onboarding_step4_title,
            R.string.onboarding_step4_title_done,
            R.string.onboarding_step4_hint,
            R.string.onboarding_step4_hint_locked,
        )
        renderStep(
            step5,
            state.permissions,
            R.string.onboarding_step5_title,
            R.string.onboarding_step5_title_done,
            R.string.onboarding_step5_hint,
            R.string.onboarding_step5_hint_locked,
        )
        permissionsStep.render(state.permissions)
        // Étape 4 : le modèle recommandé selon la mémoire du téléphone, nommé dans l'explication et sur le bouton.
        if (state.activateAi == StepStatus.CURRENT) {
            val memory = ActivityManager.MemoryInfo()
            getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            val model = ModelRecommendation.recommended(memory.totalMem)
            val name = model.displayName(this)
            val size = Formatter.formatShortFileSize(this, model.approxDownloadBytes ?: model.approxSizeBytesMax)
            step4.hint.text = getString(R.string.onboarding_step4_hint, name)
            (step4.action as? TextView)?.text = getString(R.string.onboarding_step4_button, name, size)
        }
        // Le champ d'essai reste là tant que l'étape 3 est en cours, et juste après (pour finir sa phrase).
        tryInput.visibility = if (state.tryIt == StepStatus.CURRENT || (state.tryIt == StepStatus.DONE && tryInput.hasFocus())) View.VISIBLE else View.GONE
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

    private companion object {
        const val HOME_PREFS = "home_prefs"
        const val KEY_PREMIUM_CARD_DISMISSED = "premium_card_dismissed"
        const val KEY_TRIED = "onboarding_tried"
    }
}
