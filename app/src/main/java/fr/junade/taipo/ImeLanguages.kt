package fr.junade.taipo

import android.content.Context
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents

/**
 * Langue du clavier (sortie de [TaipoIme], revue de code) : langue active selon les réglages et le système, changement par la
 * touche globe (clavier multilingue), liste des langues, mesure d'usage de la langue.
 */
internal class ImeLanguages(
    private val context: Context,
    private val ime: ImeHostContext,
    private val controller: KeyboardController,
    private val gestureTips: GestureTips,
) {

    private val preferences: KeyboardPreferences get() = ime.keyboardPreferences

    /** Dernière langue envoyée à la mesure d'usage : l'événement ne repart que si elle change (pas à chaque champ). */
    private var lastTracked: KeyboardLanguage? = null

    fun track() {
        val language = controller.state.language
        if (ime.sensitiveField || language == lastTracked) return
        lastTracked = language
        Analytics.track(
            context,
            AnalyticsEvents.KEYBOARD_LANGUAGE,
            "lang" to language.tag,
            "layout" to preferences.letterArrangementOf(language).tag,
            "auto" to (preferences.keyboardLanguage == null),
        )
    }

    /**
     * Langue active : celle choisie dans les paramètres du clavier ; en mode « automatique », la première langue
     * du système que le clavier gère, sinon l'anglais (voir [KeyboardPreferences.keyboardLanguage]).
     */
    fun current(): KeyboardLanguage =
        KeyboardLanguageChoice.resolve(
            preferences.keyboardLanguage,
            context.resources.configuration.locales.let { list -> (0 until list.size()).map { list.get(it).toLanguageTag() } },
            preferences.multilingualLanguages,
            preferences.activeMultilingualLanguage,
        )

    /**
     * Clavier multilingue : touche globe de la bulle d'appui long sur Entrée. Deux langues cochées : on passe
     * directement à l'autre ; davantage : liste de choix. Sans effet si le mode n'est pas actif.
     */
    fun onGlobeSelected() {
        gestureTips.learned(GestureTips.Tip.SWITCH_LANGUAGE)
        val languages = preferences.multilingualLanguages
        when {
            languages.size == 2 -> MultilingualChoice.next(languages, controller.state.language)?.let { switchTo(it) }
            languages.size > 2 -> showPicker(languages)
        }
    }

    /** Liste des langues cochées (au-dessus du clavier) : un appui sur une langue la rend active et ferme la liste. */
    private fun showPicker(languages: List<KeyboardLanguage>) {
        // Pas de fenêtre où accrocher la liste : on passe simplement à la langue suivante.
        fun nextLanguage() {
            MultilingualChoice.next(languages, controller.state.language)?.let { switchTo(it) }
        }
        ime.messagePopup.showChoice(
            title = R.string.keyboard_settings_language_title,
            items = languages.map { it.displayName }.toTypedArray(),
            checked = languages.indexOf(controller.state.language),
            onChosen = { which -> switchTo(languages[which]) },
            onUnavailable = ::nextLanguage,
        )
    }

    /** Passe le clavier à [next] tout de suite (disposition, dictionnaire, suggestions) et la retient pour les prochains champs. */
    private fun switchTo(next: KeyboardLanguage) {
        if (next == controller.state.language) return
        preferences.activeMultilingualLanguage = next
        ime.haptic()
        controller.setLanguage(next)
        ime.clearPendingAutocorrection()
        ime.suggestions.invalidate() // les suggestions affichées étaient celles de l'ancienne langue
        ime.suggestions.preloadDictionaries()
        track()
        ime.syncAutoCapitalization()
        ime.applyState()
    }
}
