package fr.junade.taipo

import kotlin.math.exp
import kotlin.math.sin

/** Ce que montre l'icône Transcription IA : repos (onde), enregistrement (onde au volume) ou traitement (lignes). */
internal enum class WaveIconMode {
    IDLE,
    LISTENING,
    WORKING,
    ;

    companion object {
        /** L'annulation en cours garde les lignes : le modèle n'a pas encore rendu la main. */
        fun of(state: AiTranscriptionBarState): WaveIconMode = when (state) {
            AiTranscriptionBarState.IDLE -> IDLE
            AiTranscriptionBarState.RECORDING -> LISTENING
            AiTranscriptionBarState.PROCESSING, AiTranscriptionBarState.CANCELLING -> WORKING
        }
    }
}

/** Rectangle d'une barre de l'icône, sur la grille de 24 (coin haut-gauche, largeur, hauteur). */
internal data class BarRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Pose de l'icône Transcription IA : trois barres verticales en onde. Au repos elles forment une onde fixe ; à l'écoute leur hauteur suit
 * le niveau sonore ; en traitement (transcription) elles se couchent en trois lignes de longueurs différentes qu'un
 * reflet balaie. Calculs purs (grille de 24), sans dépendance Android.
 */
internal object WaveIconPose {

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
