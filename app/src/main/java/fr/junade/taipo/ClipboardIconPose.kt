package fr.junade.taipo

import kotlin.math.sin

/**
 * Pose de l'icône Smart Clipboard animée (« Étincelles ») : à l'ouverture du panneau trois étincelles à quatre branches
 * jaillissent en tournant et scintillent, et le presse-papiers s'incline. Calculs purs, grille de 24 ; [weight] vaut 0
 * au repos et 1 panneau ouvert (il dépasse un peu pendant le ressort).
 */
internal object ClipboardIconPose {

    /** Une étincelle : centre et rayon sur la grille de 24 (elles débordent volontairement de l'icône). */
    data class Spark(val x: Float, val y: Float, val radius: Float)

    /** Inclinaison du presse-papiers panneau ouvert, en degrés (négatif : vers la gauche, côté opposé aux étincelles). */
    const val TILT_DEGREES = -8f

    /** Pivot de l'inclinaison. */
    const val PIVOT_X = 12f
    const val PIVOT_Y = 12.5f

    /** Grande étincelle en haut à droite, puis deux petites ; rayons doublés par rapport à la première maquette. */
    val SPARKS = listOf(
        Spark(21.5f, 3.2f, 5.0f),
        Spark(22.8f, 11.5f, 2.6f),
        Spark(2.4f, 3.2f, 2.8f),
    )

    private const val TWINKLE_AMPLITUDE = 0.18f
    private const val TWINKLE_SPEED = 5f
    private const val SPIN_START_DEGREES = -120f
    private const val WOBBLE_DEGREES = 10f
    private const val WOBBLE_SPEED = 2f

    fun tilt(weight: Float): Float = TILT_DEGREES * weight

    /** Taille de l'étincelle [index] : elle grandit avec [weight] puis scintille tant que le panneau est ouvert. */
    fun sparkScale(index: Int, weight: Float, timeSeconds: Float): Float {
        val open = weight.coerceIn(0f, 1f)
        val twinkle = 1f + TWINKLE_AMPLITUDE * sin(timeSeconds * TWINKLE_SPEED + index * 2.1f) * open
        return weight.coerceAtLeast(0f) * SPARKS[index].radius * twinkle
    }

    /** Rotation de l'étincelle [index] : elle arrive en tournant (de -120° à 0°), puis oscille doucement. */
    fun sparkRotation(index: Int, weight: Float, timeSeconds: Float): Float =
        (1f - weight) * SPIN_START_DEGREES + WOBBLE_DEGREES * sin(timeSeconds * WOBBLE_SPEED + index)

    /** Opacité des étincelles : pleine dès que [weight] atteint 0,5. */
    fun sparkAlpha(weight: Float): Float = (weight * 2f).coerceIn(0f, 1f)
}
