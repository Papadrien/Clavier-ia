package fr.junade.taipo

import androidx.annotation.DrawableRes

/**
 * Couleurs des suggestions d'auto-remplissage en ligne (Bitwarden…), selon le thème du clavier ([KeyboardTheme.Mode]) :
 * sombre (fond des boutons secondaires, texte blanc) ou clair (fond des boutons secondaires de la palette claire, texte sombre).
 *
 * Le service d'auto-remplissage dessine lui-même ses suggestions à partir d'un style que le clavier lui envoie ; sans
 * style, elles restent en thème clair par défaut, quel que soit le thème du clavier.
 *
 * Logique pure (sans Android), testée en JVM.
 */
internal object InlineSuggestionColors {

    /** [chipBackground] est une ressource drawable du clavier ; [title] et [subtitle] sont des couleurs ARGB. */
    data class Colors(@DrawableRes val chipBackground: Int, val title: Int, val subtitle: Int)

    // Palette claire (lot T6) : texte `taipo_text` (#1B1B1F), et sa version atténuée à 70 % composée sur la pastille #CDCDD3
    // (le service d'auto-remplissage reçoit une couleur pleine, pas la transparence de `taipo_text_muted`) : 5,1:1.
    private const val LIGHT_TITLE = 0xFF1B1B1F.toInt()
    private const val LIGHT_SUBTITLE = 0xFF505055.toInt()

    /**
     * @param darkTitle texte principal du thème sombre (celui de la palette du clavier)
     * @param darkSubtitle texte secondaire du thème sombre (celui de la palette du clavier)
     */
    fun forMode(mode: KeyboardTheme.Mode, darkTitle: Int, darkSubtitle: Int): Colors = when (mode) {
        KeyboardTheme.Mode.DARK -> Colors(R.drawable.bg_inline_chip_dark, darkTitle, darkSubtitle)
        KeyboardTheme.Mode.LIGHT -> Colors(R.drawable.bg_inline_chip_light, LIGHT_TITLE, LIGHT_SUBTITLE)
    }
}
