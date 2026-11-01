package fr.junade.taipo

/**
 * Thème du clavier choisi par l'utilisateur (réglage « Thème » : page [ThemeActivity], lot T5). Ne concerne que le clavier : les écrans
 * de l'application restent sombres (voir [KeyboardTheme.wrap]).
 *
 * - [SYSTEM] : « Système, automatique » : clair ou sombre selon le thème de l'appareil, suivi en direct ;
 * - [LIGHT] / [DARK] : thème fixe.
 *
 * - [DYNAMIC] : « Couleur dynamique » (lot T7, Android 12+) : palette tirée du fond d'écran (tons `system_accent1_*` et
 *   `system_neutral1_*`), claire ou sombre selon le thème de l'appareil comme [SYSTEM]. Sous Android 12, le choix n'est pas proposé ;
 *   un réglage dynamique lu sur un appareil plus ancien se comporte comme [SYSTEM].
 */
enum class ThemeChoice(val storageKey: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    DYNAMIC("dynamic"),
    ;

    /** Proposé sur cet appareil ? Seule « Couleur dynamique » dépend de la version d'Android ([sdk] : `Build.VERSION.SDK_INT`). */
    fun isAvailable(sdk: Int): Boolean = this != DYNAMIC || sdk >= DYNAMIC_MIN_SDK

    /** Vrai quand la palette vient du fond d'écran : le choix est [DYNAMIC] et l'appareil est assez récent. */
    fun usesDynamicColors(sdk: Int): Boolean = this == DYNAMIC && sdk >= DYNAMIC_MIN_SDK

    /** Palette à utiliser, sachant si l'appareil est en mode sombre ([systemNight]). */
    fun resolve(systemNight: Boolean): KeyboardTheme.Mode = when (this) {
        SYSTEM, DYNAMIC -> if (systemNight) KeyboardTheme.Mode.DARK else KeyboardTheme.Mode.LIGHT
        LIGHT -> KeyboardTheme.Mode.LIGHT
        DARK -> KeyboardTheme.Mode.DARK
    }

    companion object {
        /** Android 12 : première version avec les couleurs du fond d'écran (`system_accent1_*`). */
        const val DYNAMIC_MIN_SDK = 31

        /**
         * Système, automatique (lot T6) : le clavier clair est complet (texte sur violet, accents sur touches claires, pastilles
         * d'auto-remplissage), le clavier suit donc le thème de l'appareil tant que l'utilisateur n'a rien choisi.
         */
        val DEFAULT = SYSTEM

        fun fromStorageKey(key: String?): ThemeChoice = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
