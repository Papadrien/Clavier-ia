package fr.junade.taipo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Ce que montre le bouton rond de la barre du prompt : attente (étincelles en orbite), envoyer (flèche) ou stop. */
internal enum class SendIconMode { LOADING, SEND, STOP }

/** Pose de la flèche pendant le décollage : décalage vertical (grille de 24, négatif = vers le haut), étirement et opacité. */
internal data class LaunchPose(val offsetY: Float, val scaleY: Float, val alpha: Float, val visible: Boolean)

/**
 * Calculs purs de l'icône du bouton d'envoi (proposition A « Orbite », maquette validée) : position des trois étincelles
 * qui tournent, et décollage de la flèche à l'envoi. Grille de 24 comme les autres icônes.
 */
internal object SendIconPose {

    /** Un tour d'orbite. */
    const val ORBIT_PERIOD_S = 0.9f

    /** Rayon de l'orbite (maquette : 17 sur un bouton de 64, soit environ 9,6 sur 36). */
    const val ORBIT_RADIUS = 9.6f

    /** Côté de chaque étincelle = diamètre des points de la maquette (8 sur 64, soit 4,5 sur 36). */
    const val SPARKLE_SIZE = 4.5f

    /** Échelle et opacité des trois étincelles : la première est pleine, les suivantes forment une traîne. */
    val SPARKLE_SCALES = floatArrayOf(1f, 0.75f, 0.5f)
    val SPARKLE_ALPHAS = floatArrayOf(1f, 0.75f, 0.5f)

    /** Durée du décollage de la flèche, avant que l'icône suivante (flèche ou stop) remonte. */
    const val LAUNCH_S = 0.45f

    /** Durée de l'onde qui part du bouton à l'envoi. */
    const val RING_S = 0.7f
    const val RING_DELAY_S = 0.1f

    /** Distance parcourue par la flèche (maquette : 46 sur 64, soit 26 sur 36). */
    const val LAUNCH_DISTANCE = 26f

    private const val TAKEOFF_END = 0.75f

    /** Position (x, y, grille de 24, centre à l'origine) de l'étincelle [index] quand l'orbite est à [turns] tours. */
    fun sparklePosition(index: Int, turns: Float): Pair<Float, Float> {
        val angle = 2.0 * PI * (turns + index / 3f)
        return (ORBIT_RADIUS * sin(angle)).toFloat() to (-ORBIT_RADIUS * cos(angle)).toFloat()
    }

    /** Taille de l'orbite pendant qu'elle se replie (1 = pleine, 0 = disparue) : départ lent, fin rapide. */
    fun orbitScale(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p)
    }

    /** Pose de la flèche [seconds] secondes après l'appui sur envoyer : elle s'étire et s'envole vers le haut en s'effaçant. */
    fun launchPose(seconds: Float): LaunchPose {
        val p = (seconds / LAUNCH_S).coerceIn(0f, 1f)
        if (p >= TAKEOFF_END) return LaunchPose(0f, 1f, 0f, visible = false)
        val u = p / TAKEOFF_END
        val eased = u * u
        return LaunchPose(-LAUNCH_DISTANCE * eased, 1f + 0.35f * eased, 1f - eased, visible = true)
    }

    /** Avancement (0 à 1) de l'onde [seconds] secondes après l'appui ; hors de sa durée : null. */
    fun ringProgress(seconds: Float): Float? {
        val t = seconds - RING_DELAY_S
        if (t < 0f || t > RING_S) return null
        val u = t / RING_S
        return 1f - (1f - u) * (1f - u)
    }

    /** Rayon de l'onde, en part du rayon de la face : de 0,2 à 1,25 (maquette). */
    fun ringRadiusShare(progress: Float): Float = 0.2f + 1.05f * progress
}
