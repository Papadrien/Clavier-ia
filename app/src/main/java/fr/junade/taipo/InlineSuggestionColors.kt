package fr.junade.taipo

import androidx.annotation.DrawableRes

/**
 * Couleurs des suggestions d'auto-remplissage en ligne (Bitwarden…), selon le thème du clavier ([KeyboardTheme.Mode]) :
 * sombre (fond des boutons secondaires, texte blanc) ou clair (fond gris clair, texte sombre).
 *
 * Le service d'auto-remplissage dessine lui-même ses suggestions à partir d'un style que le clavier lui envoie ; sans
 * style, elles restent en thème clair par défaut, quel que soit le thème du clavier.
 *
 * Logique pure (sans Android), testée en JVM.
 */
internal object InlineSuggestionColors {

    /** [chipBackground] est une ressource drawable du clavier ; [title] et [subtitle] sont des couleurs ARGB. */
    data class Colors(@DrawableRes val chipBackground: Int, val title: Int, val subtitle: Int)

    private const val LIGHT_TITLE = 0xFF1B1B1B.toInt()
    private const val LIGHT_SUBTITLE = 0xFF5F5F5F.toInt()

    /**
     * @param darkTitle texte principal du thème sombre (celui de la palette du clavier)
     * @param darkSubtitle texte secondaire du thème sombre (celui de la palette du clavier)
     */
    fun forMode(mode: KeyboardTheme.Mode, darkTitle: Int, darkSubtitle: Int): Colors = when (mode) {
        KeyboardTheme.Mode.DARK -> Colors(R.drawable.bg_inline_chip_dark, darkTitle, darkSubtitle)
        KeyboardTheme.Mode.LIGHT -> Colors(R.drawable.bg_inline_chip_light, LIGHT_TITLE, LIGHT_SUBTITLE)
    }
}
