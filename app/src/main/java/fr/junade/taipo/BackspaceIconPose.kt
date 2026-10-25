package fr.junade.taipo

import kotlin.math.cos
import kotlin.math.exp

/**
 * Pose de l'icône retour arrière animée (« balancier » et « étincelles ») : calculs purs, en degrés. La touche, elle, ne
 * change pas (face, ombre et enfoncement restent ceux de [KeyRenderer]) : seule l'icône pivote autour de son centre.
 *
 * - Balancier : à chaque suppression l'icône part d'un angle négatif (inclinée à gauche) puis oscille de gauche à droite,
 *   en s'amortissant, avec la période d'un ressort peu amorti ([swingAngle]).
 * - Glissement vers la gauche : l'icône s'incline progressivement vers la gauche, du côté où va le doigt ([dragTilt]).
 */
internal object BackspaceIconPose {

    /** Angle de départ du balancier après un appui (négatif : vers la gauche). */
    const val SWING_TAP_DEGREES = -32f

    /** Appui maintenu (répétition) : balancier plus discret pour ne pas faire trembler l'icône. */
    const val SWING_REPEAT_DEGREES = -12f

    /** Un mot de plus sélectionné pendant le glissement. */
    const val SWING_WORD_DEGREES = -18f

    /** Relâchement après un glissement : la suppression part, l'icône oscille en revenant. */
    const val SWING_RELEASE_DEGREES = -14f

    /** Pulsation du balancier (rad/s, soit une période d'environ 0,37 s) et amortissement (1/s). */
    const val SWING_OMEGA = 17f
    const val SWING_DECAY = 3.2f

    /** Durée du balancier ; l'amplitude restante est alors inférieure à 2°. */
    const val SWING_DURATION_S = 0.9f

    /** Inclinaison de l'icône quand le glissement a atteint [DRAG_TILT_FULL_DP]. */
    const val DRAG_TILT_DEGREES = -12f
    const val DRAG_TILT_FULL_DP = 60f

    /** Angle du balancier [timeSeconds] après son départ, d'amplitude [amplitude] ; 0 avant le départ et une fois terminé. */
    fun swingAngle(timeSeconds: Float, amplitude: Float): Float {
        if (timeSeconds < 0f || timeSeconds >= SWING_DURATION_S) return 0f
        return amplitude * exp(-SWING_DECAY * timeSeconds) * cos(SWING_OMEGA * timeSeconds)
    }

    /** Inclinaison cible de l'icône pour un glissement de [distanceDp] vers la gauche (0 à [DRAG_TILT_DEGREES]). */
    fun dragTilt(distanceDp: Float): Float = DRAG_TILT_DEGREES * (distanceDp / DRAG_TILT_FULL_DP).coerceIn(0f, 1f)
}

/** Vie d'une étincelle (u de 0 à 1) : elle part vite, ralentit, rapetisse et s'éteint. */
internal object BackspaceSparkPose {

    /** Taille de l'étincelle : un point de 4 dp de diamètre, comme celles des autres icônes. */
    const val RADIUS_DP = 2f

    /** Avancement du déplacement (sortie rapide, arrivée douce). */
    fun ease(u: Float): Float {
        val rest = 1f - u.coerceIn(0f, 1f)
        return 1f - rest * rest * rest
    }

    fun alpha(u: Float): Float = (1f - u).coerceIn(0f, 1f)

    fun scale(u: Float): Float = 1f - 0.8f * u.coerceIn(0f, 1f)
}
