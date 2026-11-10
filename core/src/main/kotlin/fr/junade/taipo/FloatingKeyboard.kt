package fr.junade.taipo

import kotlin.math.roundToInt

/**
 * Clavier flottant : largeur de la carte, en % de la largeur utile de l'écran. Réglage indépendant de la largeur du mode une
 * main ([OneHandedWidth]) : le clavier flottant peut être bien plus étroit. Logique pure, testée en JVM.
 */
object FloatingWidth {
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 95
    const val DEFAULT_PERCENT = 80

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}

/**
 * Position du clavier flottant, en fractions de l'espace libre autour de la carte : `x` = 0 collée à gauche, 1 collée à
 * droite ; `y` = 0 en haut, 1 en bas. Exprimée ainsi, elle reste valable quand la taille de la carte ou de l'écran change
 * (hauteur du clavier, largeur, rotation). Toujours dans [0, 1] : passer par [of].
 */
data class FloatingPosition private constructor(val x: Float, val y: Float) {
    companion object {
        /** Première ouverture : centrée, un peu au-dessus du bas de l'écran. */
        val DEFAULT = FloatingPosition(0.5f, 0.85f)

        fun of(x: Float, y: Float): FloatingPosition =
            FloatingPosition(if (x.isNaN()) DEFAULT.x else x.coerceIn(0f, 1f), if (y.isNaN()) DEFAULT.y else y.coerceIn(0f, 1f))
    }
}

/** Zone de l'écran où la carte peut se placer (hors barres système et encoche), en pixels. */
data class FloatingArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** Coin haut-gauche de la carte, en pixels. */
data class FloatingOffset(val left: Int, val top: Int)

/** Placement et déplacement de la carte du clavier flottant. Logique pure, testée en JVM (`FloatingPlacementTest`). */
object FloatingPlacement {

    /** Coin haut-gauche de la carte [cardWidth] × [cardHeight] placée à [position] dans [area] (jamais hors de la zone). */
    fun place(position: FloatingPosition, cardWidth: Int, cardHeight: Int, area: FloatingArea): FloatingOffset {
        val freeX = (area.width - cardWidth).coerceAtLeast(0)
        val freeY = (area.height - cardHeight).coerceAtLeast(0)
        return FloatingOffset(area.left + (freeX * position.x).roundToInt(), area.top + (freeY * position.y).roundToInt())
    }

    /**
     * Position après un glissement de [dx], [dy] pixels depuis [start] (position au moment où le doigt s'est posé sur la
     * poignée). Calculée depuis le début du geste, pas pas à pas : au retour d'un bord, la carte repart sous le doigt.
     */
    fun dragged(start: FloatingPosition, dx: Float, dy: Float, cardWidth: Int, cardHeight: Int, area: FloatingArea): FloatingPosition {
        val origin = place(start, cardWidth, cardHeight, area)
        val freeX = (area.width - cardWidth).coerceAtLeast(0)
        val freeY = (area.height - cardHeight).coerceAtLeast(0)
        val x = if (freeX == 0) start.x else (origin.left + dx - area.left) / freeX
        val y = if (freeY == 0) start.y else (origin.top + dy - area.top) / freeY
        return FloatingPosition.of(x, y)
    }
}
