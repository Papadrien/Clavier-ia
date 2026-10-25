package fr.junade.taipo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/** Ce que montre l'icône micro du bouton Vocal : repos, attente (chargement / transcription) ou écoute. */
internal enum class MicIconMode {
    IDLE,
    LOADING,
    LISTENING,
    ;

    companion object {
        /** Chargement du modèle et transcription finale : le micro n'écoute pas, il « travaille ». */
        fun of(state: VoiceBarState): MicIconMode = when (state) {
            VoiceBarState.IDLE -> IDLE
            VoiceBarState.LOADING, VoiceBarState.TRANSCRIBING -> LOADING
            VoiceBarState.RECORDING -> LISTENING
        }
    }
}

/** Niveau sonore du micro, de 0 (silence) à 1 (voix forte), à partir de l'amplitude RMS d'un tampon PCM16. */
internal object VoiceLevel {
    private const val FULL_SCALE = 32768.0
    private const val FLOOR_DB = -55.0
    private const val CEILING_DB = -20.0

    /** Échelle en décibels (l'oreille est logarithmique) : une voix normale (RMS ≈ 500 à 3000) tombe entre 0,53 et 0,98 (échelle décalée de 5 dB : jauge du micro et onde plus sensibles). */
    fun fromRms(rms: Double): Float {
        if (rms <= 0.0) return 0f
        val db = 20.0 * log10(rms / FULL_SCALE)
        return ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Ressort amorti : monte vite vers sa cible, la dépasse d'environ 20 % puis se stabilise (même ressort que les maquettes). */
internal class SpringValue(var value: Float = 0f) {
    var velocity = 0f
        private set

    fun step(target: Float, dtSeconds: Float) {
        val n = ceil(dtSeconds / SUBSTEP_S).toInt().coerceAtLeast(1)
        val h = dtSeconds / n
        repeat(n) {
            velocity += (STIFFNESS * (target - value) - DAMPING * velocity) * h
            value += velocity * h
        }
    }

    fun isSettled(target: Float): Boolean = abs(target - value) < SETTLED_DISTANCE && abs(velocity) < SETTLED_SPEED

    /** Comme [isSettled] pour une valeur en degrés : la tolérance est de 0,2° et la vitesse de 1°/s. */
    fun isSettledAngle(target: Float): Boolean = abs(target - value) < SETTLED_DEGREES && abs(velocity) < SETTLED_DEGREES_PER_S

    fun snap(target: Float) {
        value = target
        velocity = 0f
    }

    private companion object {
        const val OMEGA = 20f
        const val ZETA = 0.456f
        const val STIFFNESS = OMEGA * OMEGA
        const val DAMPING = 2f * ZETA * OMEGA
        const val SUBSTEP_S = 0.004f
        const val SETTLED_DISTANCE = 0.002f
        const val SETTLED_SPEED = 0.01f
        const val SETTLED_DEGREES = 0.2f
        const val SETTLED_DEGREES_PER_S = 1f
    }
}

/**
 * Pose de l'icône micro animée. Calculs purs : le trait de la capsule qui se dessine puis s'efface (chargement), la jauge
 * qui suit le niveau sonore (écoute). Le pied (U, tige, base) ne bouge jamais.
 */
internal object MicIconPose {

    /** Durée d'un cycle « le trait se dessine, puis s'efface » en chargement. */
    const val TRACE_PERIOD_S = 2.6f

    /** Part minimale de remplissage pendant l'écoute, pour que la jauge reste visible dans le silence. */
    const val GAUGE_FLOOR = 0.08f
    const val GAUGE_MAX = 1.04f
    const val WAVE_AMPLITUDE = 0.45f
    private const val ATTACK_S = 0.05f
    private const val RELEASE_S = 0.18f

    /**
     * Portion visible du contour de la capsule, en fractions (début, fin) de son périmètre. [loadWeight] = 0 : contour entier ;
     * 1 : le trait se dessine de 0 à 1 (première moitié du cycle), puis s'efface de 0 à 1 (seconde moitié).
     */
    fun traceWindow(timeSeconds: Float, loadWeight: Float): Pair<Float, Float> {
        val tau = (timeSeconds % TRACE_PERIOD_S) / TRACE_PERIOD_S
        var start = 0f
        var end = 1f
        if (tau < 0.5f) end = ease(tau / 0.5f) else start = ease((tau - 0.5f) / 0.5f)
        val w = loadWeight.coerceIn(0f, 1f)
        return start * w to 1f + (end - 1f) * w
    }

    /** Hauteur de remplissage de la capsule (0 = vide, 1 = pleine), qui dépasse un peu 1 pendant le ressort. */
    fun gaugeFill(listenWeight: Float, micLevel: Float): Float =
        (listenWeight * (GAUGE_FLOOR + (1f - GAUGE_FLOOR) * micLevel.coerceIn(0f, 1f))).coerceIn(0f, GAUGE_MAX)

    /** Ordonnée de la surface du liquide en [x] sur la grille de 24 (la capsule va de y = 3 à 14). */
    fun surfaceY(fill: Float, x: Float, timeSeconds: Float): Float =
        CAPSULE_BOTTOM - fill * CAPSULE_HEIGHT + WAVE_AMPLITUDE * sin(x * 1.6f + timeSeconds * 8f)

    /** Lissage du niveau reçu par à-coups : monte vite (attaque), redescend plus doucement. Ne dépasse jamais la cible. */
    fun smooth(current: Float, target: Float, dtSeconds: Float): Float {
        val tau = if (target > current) ATTACK_S else RELEASE_S
        return current + (target - current) * (1f - exp(-dtSeconds / tau))
    }

    private fun ease(x: Float): Float = if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f) * (-2f * x + 2f) / 2f

    const val CAPSULE_BOTTOM = 14f
    const val CAPSULE_HEIGHT = 11f
}
