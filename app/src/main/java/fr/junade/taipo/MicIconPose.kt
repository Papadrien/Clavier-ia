package fr.junade.taipo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/** Ce que montre l'icône Vocal : repos (onde), attente (chargement / transcription : lignes) ou écoute (onde au volume). */
internal enum class MicIconMode {
    IDLE,
    LOADING,
    LISTENING,
    ;

    companion object {
        /** Chargement du modèle et transcription finale : les barres se couchent en lignes (l'appareil « travaille »). */
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
    private const val FLOOR_DB = -50.0
    private const val CEILING_DB = -15.0

    /** Échelle en décibels (l'oreille est logarithmique) : une voix normale (RMS ≈ 500 à 3000) tombe entre 0,4 et 0,85. */
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

/** Rectangle d'une barre de l'icône, sur la grille de 24 (coin haut-gauche, largeur, hauteur). */
internal data class BarRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Pose de l'icône Vocal : trois barres verticales en onde. Au repos elles forment une onde fixe ; à l'écoute leur hauteur suit
 * le niveau sonore ; en attente (chargement, transcription) elles se couchent en trois lignes de longueurs différentes qu'un
 * reflet balaie. Calculs purs (grille de 24), sans dépendance Android.
 */
internal object MicIconPose {

    const val BAR_COUNT = 3
    const val BAR_WIDTH = 3.4f
    const val BAR_PITCH = 6.2f
    const val CENTER = 12f

    /** Hauteurs au repos (onde fixe : la barre centrale domine). */
    val REST_HEIGHT = floatArrayOf(9f, 15f, 11f)

    /** Longueurs des trois lignes en attente. */
    val LINE_LENGTH = floatArrayOf(19f, 13f, 16f)

    /** Part du niveau sonore suivie par chaque barre : la centrale réagit le plus. */
    val LEVEL_GAIN = floatArrayOf(0.8f, 1f, 0.88f)

    /** Hauteur d'une barre à l'écoute dans le silence, puis à pleine voix. */
    const val LISTEN_MIN_HEIGHT = 4.5f
    const val LISTEN_MAX_HEIGHT = 19f

    /** Taille minimale d'un côté de barre : le ressort peut sous-dépasser sans que la barre disparaisse. */
    const val MIN_SIDE = 0.6f
    private const val WOBBLE_BASE = 0.82f
    private const val WOBBLE_DEPTH = 0.18f
    private const val WOBBLE_SPEED = 13f
    private const val WOBBLE_SHIFT = 2.1f
    private const val SWEEP_SPEED = 5f
    private const val SWEEP_SHIFT = 1.4f
    private const val ATTACK_S = 0.05f
    private const val RELEASE_S = 0.18f

    /**
     * Hauteur de la barre [index] quand elle est droite. [listenWeight] = 0 : hauteur de repos ; 1 : hauteur d'écoute selon
     * [micLevel] (0 à 1), avec un léger frémissement propre à chaque barre pour que l'onde reste vivante.
     */
    fun barHeight(index: Int, listenWeight: Float, micLevel: Float, timeSeconds: Float): Float {
        val wobble = WOBBLE_BASE + WOBBLE_DEPTH * sin(timeSeconds * WOBBLE_SPEED + index * WOBBLE_SHIFT)
        val level = (micLevel.coerceIn(0f, 1f) * LEVEL_GAIN[index] * wobble).coerceIn(0f, 1f)
        val listening = LISTEN_MIN_HEIGHT + (LISTEN_MAX_HEIGHT - LISTEN_MIN_HEIGHT) * level
        return lerp(REST_HEIGHT[index], listening, listenWeight)
    }

    /**
     * Rectangle de la barre [index]. [loadWeight] = 0 : barre verticale ; 1 : ligne horizontale (la barre se couche autour de
     * son centre). Le ressort peut dépasser 1 : la ligne s'étire alors un instant.
     */
    fun bar(index: Int, listenWeight: Float, loadWeight: Float, micLevel: Float, timeSeconds: Float): BarRect {
        val height = barHeight(index, listenWeight, micLevel, timeSeconds)
        val offset = (index - 1) * BAR_PITCH
        val verticalX = CENTER + offset - BAR_WIDTH / 2f
        val verticalY = CENTER - height / 2f
        val length = LINE_LENGTH[index]
        val horizontalX = CENTER - length / 2f
        val horizontalY = CENTER + offset - BAR_WIDTH / 2f
        return BarRect(
            x = lerp(verticalX, horizontalX, loadWeight),
            y = lerp(verticalY, horizontalY, loadWeight),
            width = lerp(BAR_WIDTH, length, loadWeight).coerceAtLeast(MIN_SIDE),
            height = lerp(height, BAR_WIDTH, loadWeight).coerceAtLeast(MIN_SIDE),
        )
    }

    /** Intensité (0 à 1) du reflet qui balaie la ligne [index] en attente ; décalé d'une ligne à l'autre. */
    fun sweep(index: Int, timeSeconds: Float): Float =
        0.5f + 0.5f * sin(timeSeconds * SWEEP_SPEED - index * SWEEP_SHIFT)

    /** Lissage du niveau reçu par à-coups : monte vite (attaque), redescend plus doucement. Ne dépasse jamais la cible. */
    fun smooth(current: Float, target: Float, dtSeconds: Float): Float {
        val tau = if (target > current) ATTACK_S else RELEASE_S
        return current + (target - current) * (1f - exp(-dtSeconds / tau))
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
}
