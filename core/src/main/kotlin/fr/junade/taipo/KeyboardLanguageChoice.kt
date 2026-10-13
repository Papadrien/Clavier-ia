package fr.junade.taipo

/**
 * Langue du clavier à partir du réglage de l'utilisateur : une langue choisie explicitement, ou « automatique »
 * (null) = la langue du système si le clavier la gère, sinon le français. Logique pure, testée en JVM.
 */
object KeyboardLanguageChoice {

    fun resolve(chosen: KeyboardLanguage?, systemLanguageTag: String?): KeyboardLanguage =
        chosen ?: KeyboardLanguage.fromTag(systemLanguageTag) ?: KeyboardLanguage.FR
}
