package fr.junade.taipo

/** Famille d'une touche de l'aperçu de thème (voir [ThemePreviewView]) ; les couleurs suivent celles du vrai clavier. */
internal enum class PreviewKeyKind {
    /** Lettre : touche grise (key_functional), comme sur le clavier. */
    LETTER,

    /** Maj, retour arrière, emoji : touche violet-gris (key_normal). */
    MODIFIER,

    /** Barre d'espace : touche grise avec son trait central. */
    SPACE,

    /** Entrée : touche violette. */
    ENTER,

    /** Bouton rond de la barre du haut (violet-gris). */
    BAR_BUTTON,
}

/** Une touche de l'aperçu : sa famille et son rectangle (ombre comprise), en pixels de la vue. */
internal data class PreviewKey(val kind: PreviewKeyKind, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top
    val width: Float get() = right - left
}

/**
 * Disposition de l'aperçu de thème (lot T5) : un mini clavier de quatre colonnes, une barre du haut (trois boutons ronds à
 * gauche, un à droite) et quatre rangées de touches. Pure géométrie, sans Android : testée par `ThemePreviewGeometryTest`.
 */
internal object ThemePreviewGeometry {

    /** Marge autour du clavier, écart entre les touches, hauteur de la barre : parts de la largeur ou de la hauteur de la vue. */
    private const val PADDING_RATIO = 0.08f
    private const val GAP_RATIO = 0.04f
    private const val BAR_HEIGHT_RATIO = 0.12f
    private const val COLUMNS = 4
    private const val ROWS = 4
    private const val BAR_LEFT_BUTTONS = 3

    /** Épaisseur de l'ombre d'une touche, part de la hauteur de sa case (comme `taipo_key_shadow_height` sur le vrai clavier). */
    const val SHADOW_RATIO = 0.14f

    /** Rayon des coins d'une touche, part de la hauteur de sa face. */
    const val CORNER_RATIO = 0.28f

    fun keys(width: Float, height: Float): List<PreviewKey> {
        if (width <= 0f || height <= 0f) return emptyList()
        val padding = width * PADDING_RATIO
        val gap = width * GAP_RATIO
        val barHeight = height * BAR_HEIGHT_RATIO
        val columnWidth = (width - 2 * padding - (COLUMNS - 1) * gap) / COLUMNS
        val rowsTop = padding + barHeight + gap
        val rowHeight = (height - padding - rowsTop - (ROWS - 1) * gap) / ROWS

        val keys = ArrayList<PreviewKey>()
        // Barre du haut : trois boutons ronds à gauche, un à droite.
        for (i in 0 until BAR_LEFT_BUTTONS) {
            val left = padding + i * (barHeight + gap)
            keys += PreviewKey(PreviewKeyKind.BAR_BUTTON, left, padding, left + barHeight, padding + barHeight)
        }
        keys += PreviewKey(PreviewKeyKind.BAR_BUTTON, width - padding - barHeight, padding, width - padding, padding + barHeight)

        fun cell(row: Int, firstColumn: Int, lastColumn: Int, kind: PreviewKeyKind) {
            val top = rowsTop + row * (rowHeight + gap)
            val left = padding + firstColumn * (columnWidth + gap)
            val right = padding + lastColumn * (columnWidth + gap) + columnWidth
            keys += PreviewKey(kind, left, top, right, top + rowHeight)
        }
        for (column in 0 until COLUMNS) cell(0, column, column, PreviewKeyKind.LETTER)
        for (column in 0 until COLUMNS) cell(1, column, column, PreviewKeyKind.LETTER)
        cell(2, 0, 0, PreviewKeyKind.MODIFIER)
        cell(2, 1, 1, PreviewKeyKind.LETTER)
        cell(2, 2, 2, PreviewKeyKind.LETTER)
        cell(2, 3, 3, PreviewKeyKind.MODIFIER)
        cell(3, 0, 0, PreviewKeyKind.MODIFIER)
        cell(3, 1, 2, PreviewKeyKind.SPACE)
        cell(3, 3, 3, PreviewKeyKind.ENTER)
        return keys
    }
}
