package fr.junade.taipo.suggestion

import android.text.InputType

/**
 * Stories 1.16 et 1.17 : dans quels champs la barre propose des suggestions (emoji et mots). Comme sur Gboard
 * (décision « adaptation selon le type de champ »), rien n'est proposé dans les champs qui ne sont
 * pas du texte libre (mot de passe, e-mail, adresse web, filtre, champ numérique, téléphone,
 * date), ni quand l'application demande explicitement de ne pas suggérer. Logique pure : elle ne
 * lit que des constantes.
 */
object SuggestionPolicy {

    fun allowsSuggestions(inputType: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
            -> false
            else -> true
        }
    }
}
