package fr.junade.taipo

import kotlin.math.ceil

/**
 * Rotation de l'icône Paramètres pendant l'ouverture de l'application (calculs purs, en degrés). L'angle visé avance à
 * vitesse constante tant que l'application s'ouvre ; l'icône le suit avec un ressort ([SpringValue]) : elle démarre en
 * douceur, tourne, puis, quand le tour s'arrête, se pose sur un tour entier en dépassant un peu son point d'arrivée.
 */
internal object SpinIconPose {

    /** Vitesse de l'angle visé : un tour et demi par seconde. */
    const val SPIN_DEGREES_PER_S = 540f

    /** Durée maximale de la rotation si le clavier reste affiché (l'application met du temps à s'ouvrir). */
    const val MAX_SPIN_S = 2f

    private const val TURN = 360f

    /** Angle visé après [seconds] de rotation. */
    fun spinTarget(seconds: Float): Float = SPIN_DEGREES_PER_S * seconds.coerceIn(0f, MAX_SPIN_S)

    /** Angle d'arrivée quand la rotation s'arrête à [target] : le prochain tour entier, pour retrouver la pose de repos. */
    fun settleTarget(target: Float): Float = ceil(target / TURN) * TURN
}
