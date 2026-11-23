package fr.junade.taipo

/**
 * Pose de l'icône du bouton IA animée « Panneau qui se dessine » (choix du 21/11/2026) : à l'ouverture du panneau IA, les deux
 * étincelles de `ic_ai_panel.xml` se fondent en quatre cases arrondies (le panneau en miniature), qui apparaissent l'une après
 * l'autre ; à la fermeture, les cases redeviennent étincelles. Calculs purs, grille de 24 ; [weight] vaut 0 au repos (étincelles)
 * et 1 panneau ouvert (il dépasse un peu pendant le ressort).
 */
internal object AiPanelIconPose {

    /** Une case : coin haut gauche sur la grille de 24 (côté [TILE_SIZE]). */
    data class Tile(val x: Float, val y: Float)

    val TILES = listOf(Tile(5f, 5f), Tile(13f, 5f), Tile(5f, 13f), Tile(13f, 13f))
    const val TILE_SIZE = 6f
    const val TILE_RADIUS = 1.8f

    /** Centre du rétrécissement des étincelles. */
    const val CENTER = 12f

    /** Les étincelles ont disparu quand [weight] atteint 1 / [SPARK_FADE] (≈ 0,45). */
    private const val SPARK_FADE = 2.2f
    private const val SPARK_MIN_SCALE = 0.4f

    /** Départ de la première case, puis décalage entre deux cases (en part de [weight]). */
    private const val TILE_START = 0.15f
    private const val TILE_STAGGER = 0.08f

    /** Opacité des étincelles. */
    fun sparkAlpha(weight: Float): Float = (1f - weight * SPARK_FADE).coerceIn(0f, 1f)

    /** Échelle des étincelles (elles rétrécissent en s'effaçant). */
    fun sparkScale(weight: Float): Float = SPARK_MIN_SCALE + (1f - SPARK_MIN_SCALE) * sparkAlpha(weight)

    /**
     * Échelle de la case [index] : 0 avant son tour, puis elle grandit jusqu'à 1 (et dépasse un peu avec le ressort). Les cases
     * partent l'une après l'autre.
     */
    fun tileScale(index: Int, weight: Float): Float {
        val start = TILE_START + index * TILE_STAGGER
        if (weight <= start) return 0f
        return (weight - start) / (1f - start)
    }
}
