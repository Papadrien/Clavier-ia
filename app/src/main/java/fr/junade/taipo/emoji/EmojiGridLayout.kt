package fr.junade.taipo.emoji

/** Une section du panneau : sa catégorie, son titre, ses emojis et le message affiché si elle est vide. */
data class EmojiSection(
    val id: EmojiCategoryId,
    val title: String,
    val emojis: List<String>,
    val emptyMessage: String? = null,
)

/** Emoji touché, avec le coin haut-gauche de sa cellule (coordonnées du contenu). */
data class EmojiHit(val emoji: String, val left: Float, val top: Float)

/** Lot 20 : cellule d'emoji avec sa place dans la grille (section et rang dans la section), pour l'arbre d'accessibilité. */
data class EmojiCellRef(val section: Int, val index: Int, val emoji: String, val left: Float, val top: Float)

/**
 * Story 1.15 : disposition verticale continue du panneau emoji, comme sur Gboard. Chaque section
 * commence par un en-tête (son titre), suivi de ses emojis en grille de [columns] colonnes de
 * cellules carrées de côté [cellSize]. Les coordonnées sont celles du contenu, en pixels : y = 0 est
 * le haut de la première section, avant tout défilement. Logique pure (sans Android), testée en JVM.
 */
class EmojiGridLayout(
    val sections: List<EmojiSection>,
    val columns: Int,
    val cellSize: Float,
    val headerHeight: Float,
) {

    /** Reçoit les éléments visibles, dans l'ordre du haut vers le bas ; [y] est le haut de l'élément. */
    interface Visitor {
        fun header(section: Int, y: Float)
        fun message(section: Int, y: Float)
        fun cell(emoji: String, x: Float, y: Float)

        /**
         * Lot 20 : comme [cell], avec la section et le rang de l'emoji dans sa section. Par défaut, délègue à [cell] : les
         * visiteurs existants n'ont rien à changer.
         */
        fun cellAt(section: Int, index: Int, emoji: String, x: Float, y: Float) = cell(emoji, x, y)
    }

    private val tops = FloatArray(sections.size)

    /** Hauteur totale du contenu. */
    val contentHeight: Float

    init {
        require(columns > 0) { "columns doit être positif" }
        require(cellSize > 0f) { "cellSize doit être positif" }
        var y = 0f
        sections.forEachIndexed { index, section ->
            tops[index] = y
            y += headerHeight + bodyRows(section) * cellSize
        }
        contentHeight = y
    }

    /** Nombre de rangées sous l'en-tête : celles des emojis, ou une seule pour le message d'une section vide. */
    private fun bodyRows(section: EmojiSection): Int = when {
        section.emojis.isNotEmpty() -> (section.emojis.size + columns - 1) / columns
        section.emptyMessage != null -> 1
        else -> 0
    }

    fun sectionTop(index: Int): Float = tops[index]

    /** Défilement maximal pour une zone visible de [viewportHeight] pixels. */
    fun maxScroll(viewportHeight: Float): Float = (contentHeight - viewportHeight).coerceAtLeast(0f)

    /** Indice de la section qui contient l'ordonnée [y] du contenu (-1 s'il n'y a aucune section). */
    fun sectionAt(y: Float): Int {
        if (sections.isEmpty()) return -1
        var result = 0
        for (i in tops.indices) {
            if (tops[i] <= y) result = i else break
        }
        return result
    }

    /** Emoji situé au point (x, y) du contenu, ou null (en-tête, message, cellule vide, hors grille). */
    fun hitTest(x: Float, y: Float): EmojiHit? = locate(x, y)?.let { EmojiHit(it.emoji, it.left, it.top) }

    /** Comme [hitTest], avec la section et le rang de l'emoji dans sa section (lot 20). */
    fun locate(x: Float, y: Float): EmojiCellRef? {
        if (x < 0f || y < 0f || y >= contentHeight) return null
        val sectionIndex = sectionAt(y)
        if (sectionIndex < 0) return null
        val section = sections[sectionIndex]
        val bodyTop = tops[sectionIndex] + headerHeight
        if (y < bodyTop) return null
        val col = (x / cellSize).toInt()
        if (col >= columns) return null
        val row = ((y - bodyTop) / cellSize).toInt()
        val index = row * columns + col
        val emoji = section.emojis.getOrNull(index) ?: return null
        return EmojiCellRef(sectionIndex, index, emoji, col * cellSize, bodyTop + row * cellSize)
    }

    /** Parcourt les éléments qui touchent la zone [top, bottom) du contenu. */
    fun forEachVisible(top: Float, bottom: Float, visitor: Visitor) {
        if (sections.isEmpty()) return
        var i = sectionAt(top)
        while (i in sections.indices) {
            val sectionTop = tops[i]
            if (sectionTop >= bottom) break
            val section = sections[i]
            val bodyTop = sectionTop + headerHeight
            if (bodyTop > top) visitor.header(i, sectionTop)
            if (section.emojis.isEmpty()) {
                if (section.emptyMessage != null && bodyTop + cellSize > top && bodyTop < bottom) {
                    visitor.message(i, bodyTop)
                }
            } else {
                val rows = bodyRows(section)
                var row = maxOf(0, ((top - bodyTop) / cellSize).toInt())
                while (row < rows) {
                    val y = bodyTop + row * cellSize
                    if (y >= bottom) break
                    val first = row * columns
                    val end = minOf(first + columns, section.emojis.size)
                    for (k in first until end) {
                        visitor.cellAt(i, k, section.emojis[k], (k - first) * cellSize, y)
                    }
                    row++
                }
            }
            i++
        }
    }

    companion object {
        /** Nombre de colonnes pour une largeur de [widthPx], avec des cellules d'au moins [minCellPx]. */
        fun columnsFor(widthPx: Float, minCellPx: Float): Int =
            if (minCellPx <= 0f) 1 else maxOf(1, (widthPx / minCellPx).toInt())
    }
}
