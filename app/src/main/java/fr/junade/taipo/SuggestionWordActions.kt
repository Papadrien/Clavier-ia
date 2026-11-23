package fr.junade.taipo

import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Appui long sur un mot de la bande de suggestions (23/11/2026) : un pop-up propose « Ajouter au dictionnaire » ou « Retirer du
 * dictionnaire » (dictionnaire personnel). Logique pure, testée en JVM (`SuggestionWordActionsTest`).
 */
object SuggestionWordActions {

    /** Les adresses e-mail ne sont pas des mots du dictionnaire : pas de pop-up sur elles. */
    fun canEdit(suggestion: WordSuggestion): Boolean = suggestion.kind != WordSuggestion.Kind.EMAIL
}
