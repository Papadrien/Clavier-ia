package fr.junade.taipo

import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Appui long sur un mot de la bande de suggestions (23/11/2026, comme Gboard) : le mot se détache et suit le doigt, une poubelle
 * apparaît au milieu de la bande. Relâché ailleurs, le mot est ajouté au dictionnaire personnel ; déposé sur la poubelle, il en
 * est retiré. Logique pure, testée en JVM (`SuggestionWordDragTest`).
 */
object SuggestionWordDrag {

    /** Les adresses e-mail ne sont pas des mots du dictionnaire : pas d'appui long sur elles. */
    fun canDrag(suggestion: WordSuggestion): Boolean = suggestion.kind != WordSuggestion.Kind.EMAIL

    /**
     * Le doigt ([x], [y], coordonnées de la bande) est sur la poubelle centrée en [centerX] : à moins d'une fois et demie son
     * rayon [radius] en largeur, et pas loin au-dessus ni au-dessous de la bande de hauteur [height] (le doigt glisse vite).
     */
    fun isOverTrash(x: Float, y: Float, centerX: Float, radius: Float, height: Float): Boolean =
        kotlin.math.abs(x - centerX) <= radius * TRASH_REACH && y >= -height && y <= height * 2

    private const val TRASH_REACH = 1.6f
}
