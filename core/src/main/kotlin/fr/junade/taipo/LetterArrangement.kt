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

    /** Suédois et finnois : å à droite du P, ö et ä à droite du L (11 touches sur les deux premières rangées). */
    QWERTY_SV("qwerty_sv", "QWERTY (Å Ä Ö)", listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm")),

    /** Danois : å à droite du P, æ et ø à droite du L. */
    QWERTY_DA("qwerty_da", "QWERTY (Æ Ø Å)", listOf("qwertyuiopå", "asdfghjklæø", "zxcvbnm")),

    /** Norvégien : comme le danois, avec ø avant æ. */
    QWERTY_NO("qwerty_no", "QWERTY (Ø Æ Å)", listOf("qwertyuiopå", "asdfghjkløæ", "zxcvbnm")),
    ;

    /** Touches de la rangée la plus longue : 10, ou 11 pour les QWERTY nordiques (å, ä, ö / æ, ø). C'est aussi la largeur en unités. */
    val keysPerRow: Int get() = previewRows.maxOf { it.length }

    companion object {
        /**
         * Disposition d'origine d'une langue : AZERTY en français, QWERTZ en allemand, tchèque et hongrois, QWERTY avec Ñ en espagnol,
         * QWERTY nordiques (å ä ö, æ ø å) en suédois, finnois, danois et norvégien, QWERTY sinon.
         */
        fun defaultFor(language: KeyboardLanguage): LetterArrangement = when (language) {
            KeyboardLanguage.FR -> AZERTY
            KeyboardLanguage.DE, KeyboardLanguage.CS, KeyboardLanguage.HU -> QWERTZ
            KeyboardLanguage.ES -> QWERTY_N
            KeyboardLanguage.SV, KeyboardLanguage.FI -> QWERTY_SV
            KeyboardLanguage.DA -> QWERTY_DA
            KeyboardLanguage.NB -> QWERTY_NO
            else -> QWERTY
        }

        /** La disposition de code [tag], ou null (valeur absente ou inconnue : on retombe alors sur [defaultFor]). */
        fun fromTag(tag: String?): LetterArrangement? = entries.firstOrNull { it.tag == tag }
    }
}
