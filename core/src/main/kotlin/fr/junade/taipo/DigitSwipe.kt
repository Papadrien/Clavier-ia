package fr.junade.taipo

import kotlin.math.abs

/**
 * Glissement vers le bas sur une touche de la rangée du haut pour saisir son chiffre (comme sur le clavier iOS), quand la
 * rangée de chiffres est désactivée (seules ces touches portent un `longPressChar`).
 *
 * Revue du 09/11/2026 (chiffres saisis sans le vouloir) : en frappe rapide, le doigt arrive souvent sur une touche du haut
 * en descendant déjà vers la touche suivante, et glisse un peu en se levant. Le geste est donc plus exigeant :
 * - plus long : [THRESHOLD_RATIO] de la hauteur de la touche (au lieu de 40 %) ;
 * - plutôt vertical : au moins [VERTICAL_RATIO] fois plus vers le bas que de côté (un doigt qui part vers la touche
 *   suivante va souvent en diagonale). Revue du 10/11/2026 : 2 était trop strict (un pouce ne descend pas droit), le
 *   geste accepte désormais jusqu'à environ 39° de la verticale, au lieu de 27° ;
 * - pas trop bref : au moins [MIN_DURATION_MS] entre l'appui et le chiffre (un appui de frappe dure moins longtemps) ;
 * - tant que le doigt descend ([keepsKey]), la touche du haut reste visée : un geste trop court saisit sa lettre, même si le
 *   doigt s'est levé sur la rangée du dessous.
 *
 * Logique pure, testée en JVM (`DigitSwipeTest`).
 */
object DigitSwipe {
    /** Part de la hauteur de la touche à parcourir vers le bas pour valider le geste. */
    const val THRESHOLD_RATIO = 0.6f

    /** Le déplacement vers le bas doit valoir au moins autant de fois le déplacement de côté. */
    const val VERTICAL_RATIO = 1.25f

    /** Durée minimale (ms) entre l'appui et la saisie du chiffre. */
    const val MIN_DURATION_MS = 120L

    /** Distance minimale (px) : jamais en dessous, pour ne pas confondre le geste avec un simple tremblement du doigt. */
    fun threshold(keyHeightPx: Float, touchSlopPx: Float): Float = maxOf(keyHeightPx * THRESHOLD_RATIO, touchSlopPx * 2f)

    /**
     * Vrai si le déplacement (dx, dy) depuis l'appui, [elapsedMs] après lui, saisit le chiffre : assez long, vers le bas,
     * nettement vertical, et pas trop rapide.
     */
    fun isTriggered(dx: Float, dy: Float, thresholdPx: Float, elapsedMs: Long): Boolean =
        dy >= thresholdPx && dy >= VERTICAL_RATIO * abs(dx) && elapsedMs >= MIN_DURATION_MS

    /** Avancement du geste, de 0 (pas engagé : vers le haut ou plus horizontal que vertical) à 1 (seuil franchi), pour l'animation. */
    fun progress(dx: Float, dy: Float, thresholdPx: Float): Float =
        if (thresholdPx > 0f && dy > abs(dx)) (dy / thresholdPx).coerceIn(0f, 1f) else 0f

    /**
     * Vrai si le doigt descend depuis l'appui (au-delà de la tolérance [touchSlopPx], plus vers le bas que de côté) : c'est
     * encore la touche du haut qui est visée, pas celle sur laquelle le doigt arrive.
     */
    fun keepsKey(dx: Float, dy: Float, touchSlopPx: Float): Boolean = dy > touchSlopPx && dy >= abs(dx)
}
