package fr.junade.taipo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pose de l'icône Correction animée (« Scanner ») : pendant la correction un faisceau balaie l'icône ; une fois la
 * correction réussie, la coche s'efface et se retrace d'un trait avec un rebond. Calculs purs, grille de 24.
 * `doneTime` : secondes écoulées depuis la fin de la correction, négatif s'il n'y a pas d'animation de fin en cours.
 */
internal object CorrectIconPose {

    /** Durée de l'animation de fin (retracé de la coche + rebond). */
    const val DONE_DURATION_S = 1.3f

    /** Durée d'un aller-retour du faisceau. */
    const val BEAM_PERIOD_S = 1.3f

    /** Instant où le faisceau est au milieu de l'icône (pose figée quand les animations système sont coupées). */
    const val BEAM_CENTERED_TIME_S = BEAM_PERIOD_S / 4f

    const val BEAM_MIN_X = 2f
    const val BEAM_MAX_X = 22f
    const val BEAM_HALF_WIDTH = 2.2f
    private const val ERASE_S = 0.12f
    private const val DRAW_S = 0.45f
    private const val POP_START_S = ERASE_S + DRAW_S - 0.12f

    /** Abscisse du faisceau : va et vient de [BEAM_MIN_X] à [BEAM_MAX_X]. */
    fun beamX(timeSeconds: Float): Float =
        BEAM_MIN_X + (BEAM_MAX_X - BEAM_MIN_X) * (0.5f - 0.5f * cos(2f * PI.toFloat() * timeSeconds / BEAM_PERIOD_S))

    /** Part de la coche tracée (0 à 1) : elle s'efface vite, puis se retrace. */
    fun checkDrawn(doneTime: Float): Float = when {
        doneTime < 0f -> 1f
        doneTime < ERASE_S -> 1f - doneTime / ERASE_S
        doneTime < ERASE_S + DRAW_S -> ease((doneTime - ERASE_S) / DRAW_S)
        else -> 1f
    }

    /** Échelle de la coche : petit rebond (environ +28 %, amorti) juste avant la fin du retracé. */
    fun checkScale(doneTime: Float): Float {
        if (doneTime < POP_START_S) return 1f
        val u = doneTime - POP_START_S
        return 1f + 0.28f * exp(-7f * u) * sin(16f * u)
    }

    /** Opacité de l'icône « éteinte » (hors faisceau) : pleine au repos, atténuée pendant la correction. */
    fun dimAlpha(workWeight: Float): Float = 1f - 0.65f * workWeight.coerceIn(0f, 1f)

    /** Opacité de l'icône « éclairée » par le faisceau. */
    fun brightAlpha(workWeight: Float): Float = workWeight.coerceIn(0f, 1f)

    fun beamAlpha(workWeight: Float): Float = 0.9f * workWeight.coerceIn(0f, 1f)

    private fun ease(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return if (v < 0.5f) 2f * v * v else 1f - (-2f * v + 2f) * (-2f * v + 2f) / 2f
    }
}
