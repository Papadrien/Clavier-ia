package fr.junade.taipo

/**
 * Langue active du clavier : elle décide de la disposition (AZERTY pour le français, QWERTZ pour l'allemand,
 * QWERTY pour les autres), des accents de l'appui long, du dictionnaire et de l'autocorrection.
 *
 * [tag] : code de langue (BCP 47), qui sert aussi de nom au fichier de dictionnaire (`dictionaries/<tag>.txt`) et de valeur
 * stockée dans les réglages ; [displayName] : nom de la langue dans cette langue (autonyme), tel qu'affiché dans les paramètres.
 * L'ordre de déclaration est celui de l'écran des paramètres ; FR reste la première (langue par défaut).
 */
enum class KeyboardLanguage(val tag: String, val displayName: String) {
    FR("fr", "Français"),
    EN("en", "English"),
    ES("es", "Español"),
    DE("de", "Deutsch"),
    IT("it", "Italiano"),
    PT("pt", "Português"),
    NL("nl", "Nederlands"),
    ;

    companion object {
        /** La langue de code [tag] (« fr », « pt-BR »... : seule la partie avant le tiret compte, casse ignorée), ou null. */
        fun fromTag(tag: String?): KeyboardLanguage? {
            val code = tag?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
            return entries.firstOrNull { it.tag == code }
        }
    }
}
