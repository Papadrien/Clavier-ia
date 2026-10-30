package fr.junade.taipo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.dictionary.DictionaryFiles
import fr.junade.taipo.dictionary.download.DictionaryDownloadScheduler
import fr.junade.taipo.dictionary.download.DictionaryDownloadTracker

/**
 * Écran « Langues » (paramètres du clavier > Langues, piste C) : une ligne résumée par langue utilisée (nom, disposition,
 * dictionnaire à télécharger le cas échéant) ; un appui ouvre le détail de la langue ([LanguageDetailActivity]) ;
 * « Ajouter une langue » en dernière ligne. Dès qu'il y a plusieurs langues, le clavier est multilingue : la liste remplace
 * l'ancienne liste déroulante, l'interrupteur multilingue et les cases (voir [LanguageList] pour la cohérence du réglage).
 */
class LanguagesActivity : Activity() {

    private lateinit var preferences: KeyboardPreferences
    private lateinit var list: LinearLayout

    /** État d'un téléchargement de dictionnaire modifié : la pastille « À télécharger » des lignes suit. */
    private val downloadListener = DictionaryDownloadTracker.Listener { _, _ -> runOnUiThread { render() } }

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_languages)
        preferences = KeyboardPreferences(this)
        list = findViewById(R.id.languages_list)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.languages_heading), true)
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
    }

    override fun onStart() {
        super.onStart()
        DictionaryDownloadTracker.addListener(downloadListener)
        // Le dictionnaire de chaque langue utilisée se télécharge tout seul (comme avant, à l'ouverture des paramètres).
        preferences.languageSetup.inUse(automaticKeyboardLanguage()).forEach { DictionaryDownloadScheduler.ensure(this, it) }
        render() // relue à chaque retour : le détail d'une langue a pu changer sa disposition ou la retirer
    }

    override fun onStop() {
        DictionaryDownloadTracker.removeListener(downloadListener)
        super.onStop()
    }

    private fun render() {
        val automatic = automaticKeyboardLanguage()
        val setup = preferences.languageSetup
        val inflater = LayoutInflater.from(this)
        list.removeAllViews()
        setup.inUse(automatic).forEachIndexed { index, language ->
            if (index > 0) list.addView(divider())
            list.addView(languageRow(inflater, language))
        }
        if (LanguageList.available(setup, automatic).isNotEmpty()) {
            list.addView(divider())
            list.addView(inflater.inflate(R.layout.view_language_add_row, list, false).apply { setOnClickListener { showAddDialog() } })
        }
        list.applyTaipoFontToTree()
    }

    private fun languageRow(inflater: LayoutInflater, language: KeyboardLanguage): View {
        val row = inflater.inflate(R.layout.view_settings_row, list, false)
        val arrangement = preferences.letterArrangementOf(language).displayName
        row.findViewById<TextView>(R.id.row_title).text = language.displayName
        row.findViewById<TextView>(R.id.row_subtitle).text =
            if (language == deviceKeyboardLanguage()) getString(R.string.languages_summary_device, arrangement) else arrangement
        // Dictionnaire pas encore là (téléchargement en cours, en attente ou échoué) : même pastille que l'accueil.
        row.findViewById<TextView>(R.id.row_badge).apply {
            if (DictionaryFiles.isInstalled(filesDir, language)) {
                visibility = View.GONE
            } else {
                setText(R.string.home_status_to_download)
                setBackgroundResource(R.drawable.bg_status_badge_attention)
                setTextColor(getColor(R.color.settings_warning_text))
                visibility = View.VISIBLE
            }
        }
        row.setOnClickListener {
            startActivity(Intent(this, LanguageDetailActivity::class.java).putExtra(LanguageDetailActivity.EXTRA_LANGUAGE, language.tag))
        }
        return row
    }

    /** Trait fin entre deux lignes de la carte, aligné sur le texte (comme les cartes de l'accueil). */
    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.taipo_settings_divider_thickness),
        ).apply { marginStart = resources.getDimensionPixelSize(R.dimen.taipo_settings_card_padding) }
        setBackgroundColor(getColor(R.color.settings_divider))
    }

    /** « Ajouter une langue » : liste des langues pas encore utilisées. */
    private fun showAddDialog() {
        val options = LanguageList.available(preferences.languageSetup, automaticKeyboardLanguage())
        if (options.isEmpty()) return
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setTitle(R.string.languages_add)
            .setItems(options.map { it.displayName }.toTypedArray()) { _, which -> addLanguage(options[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addLanguage(language: KeyboardLanguage) {
        preferences.languageSetup = LanguageList.add(preferences.languageSetup, automaticKeyboardLanguage(), language)
        DictionaryDownloadScheduler.ensure(this, language) // le dictionnaire de la nouvelle langue se télécharge tout de suite
        Analytics.setting(this, "language_added", language.tag)
        render()
    }
}

/** Langues du système par ordre de préférence (codes BCP 47). */
internal fun Context.systemLanguageTags(): List<String> =
    resources.configuration.locales.let { locales -> (0 until locales.size()).map { locales.get(it).toLanguageTag() } }

/** Première langue du système que le clavier gère, ou null si aucune. */
internal fun Context.deviceKeyboardLanguage(): KeyboardLanguage? =
    systemLanguageTags().firstNotNullOfOrNull { KeyboardLanguage.fromTag(it) }

/** Langue du clavier en mode « automatique » : celle du système, sinon l'anglais ([KeyboardLanguageChoice.resolve]). */
internal fun Context.automaticKeyboardLanguage(): KeyboardLanguage = KeyboardLanguageChoice.resolve(null, systemLanguageTags())
