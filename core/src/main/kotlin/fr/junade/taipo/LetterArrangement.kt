package fr.junade.taipo

/**
 * Disposition des lettres du clavier, au choix pour chaque langue (écran « Langues » > détail d'une langue).
 *
 * [tag] : valeur stockée dans les réglages et envoyée à la mesure d'usage ; [displayName] : nom affiché (identique dans
 * toutes les langues de l'interface) ; [previewRows] : les trois rangées de lettres, pour dessiner l'aperçu de la
 * disposition dans la liste de choix (un carré par touche).
 */
enum class LetterArrangement(val tag: String, val displayName: String, val previewRows: List<String>) {
    AZERTY("azerty", "AZERTY", listOf("azertyuiop", "qsdfghjklm", "wxcvbn")),
    QWERTY("qwerty", "QWERTY", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
    QWERTZ("qwertz", "QWERTZ", listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),
    QWERTY_N("qwerty_n", "QWERTY (Ñ)", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")),
    COLEMAK("colemak", "Colemak", listOf("qwfpgjluy'", "arstdhneio", "zxcvbkm")),
    ;

    companion object {
        /** Disposition d'origine d'une langue : AZERTY en français, QWERTZ en allemand, QWERTY avec Ñ en espagnol, QWERTY sinon. */
        fun defaultFor(language: KeyboardLanguage): LetterArrangement = when (language) {
            KeyboardLanguage.FR -> AZERTY
            KeyboardLanguage.DE -> QWERTZ
            KeyboardLanguage.ES -> QWERTY_N
            else -> QWERTY
        }

        /** La disposition de code [tag], ou null (valeur absente ou inconnue : on retombe alors sur [defaultFor]). */
        fun fromTag(tag: String?): LetterArrangement? = entries.firstOrNull { it.tag == tag }
    }
}
