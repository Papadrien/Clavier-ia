package fr.junade.taipo.suggestion

import android.text.InputType

/**
 * Stories 1.16 et 1.17 : dans quels champs la barre propose des suggestions (emoji et mots). Comme sur Gboard
 * (décision « adaptation selon le type de champ »), rien n'est proposé dans les champs qui ne sont
 * pas du texte libre (mot de passe, e-mail, filtre, champ numérique, téléphone, date). Les champs d'adresse
 * web (barre d'adresse et de recherche d'un navigateur) reçoivent des suggestions de mots, sans autocorrection (voir
 * `FieldType.URL`), sans apprentissage ni emoji (voir [allowsLearning], [allowsEmojiSuggestions]). L'indicateur `NO_SUGGESTIONS` posé par l'application est volontairement ignoré (Google Keep,
 * par exemple, le pose alors que les suggestions y sont utiles) : il vise la correction orthographe
 * du clavier système. Logique pure : elle ne lit que des constantes.
 */
object SuggestionPolicy {

    fun allowsSuggestions(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            -> false
            else -> true
        }
    }

    private fun isWebAddress(inputType: Int): Boolean =
        inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_URI

    /**
     * Audit SEC-02 : champ d'une ligne où l'application refuse les suggestions (identifiant, code, numéro de commande…).
     * Les suggestions y restent proposées (voir plus haut : Google Keep pose l'indicateur), mais rien n'y est appris : ce
     * qu'on y tape n'est pas du langage courant. Une note multiligne (Keep) n'est pas concernée.
     */
    private fun refusesSuggestionsOnOneLine(inputType: Int): Boolean =
        inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0 && inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0

    /**
     * Apprentissage des mots et des habitudes (prédiction du mot suivant, départage des corrections) : jamais dans une
     * adresse web, où l'on tape des adresses et des recherches qui n'ont pas à être retenues, ni dans un champ d'une ligne
     * qui refuse les suggestions ([refusesSuggestionsOnOneLine]).
     */
    fun allowsLearning(inputType: Int): Boolean =
        allowsSuggestions(inputType) && !isWebAddress(inputType) && !refusesSuggestionsOnOneLine(inputType)

    /** Emoji suggérés d'après le mot tapé : pas dans une adresse web, où ils n'ont aucun sens. */
    fun allowsEmojiSuggestions(inputType: Int): Boolean = allowsSuggestions(inputType) && !isWebAddress(inputType)

    /**
     * Adresses e-mail déjà tapées (apprentissage et suggestions) : tout champ de texte, y compris les champs
     * d'adresse e-mail où elles servent le plus, sauf les mots de passe, les adresses web et les filtres.
     */
    fun allowsEmailSuggestions(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            -> false
            else -> true
        }
    }

    /**
     * Story 2.2 : la puce de collage est proposée dans tout champ de texte. Contrairement aux suggestions de mots, elle
     * accepte aussi les champs e-mail, URL ou filtre, où coller est justement utile, et les champs de mot de passe (texte
     * ou code PIN numérique) : on y colle souvent un mot de passe ou un code copié depuis un gestionnaire. Une copie signalée
     * sensible (Bitwarden…) y reste masquée (« •••• »), comme ailleurs. Le panneau Smart Clipboard, lui, reste grisé dans
     * ces champs (story 10.1, voir `SensitiveFieldPolicy`).
     */
    fun allowsPasteSuggestion(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> true
        InputType.TYPE_CLASS_NUMBER -> inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}
