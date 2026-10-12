package fr.junade.taipo

import android.text.InputType

/**
 * Story 10.1 : champs sensibles, où les actions IA (Corriger, Générer, Vocal, Transcription IA) et le Smart Clipboard sont
 * grisés. Sont sensibles les champs de mot de passe : texte masqué, visible ou web, et champ numérique de type mot de passe
 * (code PIN). Les autres champs (e-mail, URL, téléphone, terminal, texte libre) ne changent pas.
 *
 * Décision ouverte (backlog 10.1) : `IME_FLAG_NO_PERSONALIZED_LEARNING` (navigation privée) n'est pas traité comme sensible :
 * il coupe seulement l'apprentissage (voir `learningAllowed` dans l'IME).
 *
 * Logique pure : elle ne lit que des constantes, testée en JVM.
 */
object SensitiveFieldPolicy {

    private val textPasswordVariations = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    fun isSensitive(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in textPasswordVariations
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
