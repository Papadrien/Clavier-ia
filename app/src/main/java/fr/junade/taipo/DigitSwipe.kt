package fr.junade.taipo

import kotlin.math.abs

/**
 * Glissement vers le bas sur une touche de la rangée du haut pour saisir son chiffre (comme sur le clavier iOS), quand la
 * rangée de chiffres est désactivée (seules ces touches portent un [Key.longPressChar]).
 */
internal object DigitSwipe {
    /** Part de la hauteur de la touche à parcourir vers le bas pour valider le geste. */
    const val THRESHOLD_RATIO = 0.4f

    /** Distance minimale (px) : jamais en dessous, pour ne pas confondre le geste avec un simple tremblement du doigt. */
    fun threshold(keyHeightPx: Float, touchSlopPx: Float): Float = maxOf(keyHeightPx * THRESHOLD_RATIO, touchSlopPx * 2f)

    /** Vrai si le déplacement (dx, dy) depuis l'appui est assez long, vers le bas, et plus vertical qu'horizontal. */
    fun isTriggered(dx: Float, dy: Float, thresholdPx: Float): Boolean = dy >= thresholdPx && dy > abs(dx)
}
