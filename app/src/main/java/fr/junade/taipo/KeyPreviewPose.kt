package fr.junade.taipo

import kotlin.math.max
import kotlin.math.min

/**
 * Animation de la bulle d'agrandissement des touches (page « Apparence », section « Animations », rangée « Bulle des touches »),
 * choisie le 16/11/2026 parmi quatre maquettes. Ressort par défaut ; toutes gratuites. La bulle reste au moins [KeyPreviewPose.HOLD_MS]
 * après le relâchement, quelle que soit l'animation.
 */
enum class KeyPreviewAnimation(val storageKey: String) {
    /** Sort de la touche en grandissant avec un rebond, y rentre en se réduisant. */
    SPRING("spring"),

    /** Entrée discrète ; au relâchement, la lettre s'envole vers le texte en s'effaçant. */
    FLIGHT("flight"),

    /** La touche s'étire vers le haut comme une goutte et devient la bulle, puis se rétracte. */
    DROP("drop"),

    /** Une seule bulle qui suit la frappe : elle glisse de touche en touche au lieu de s'éteindre et se rallumer. */
    GLIDE("glide"),
    ;

    companion object {
        val DEFAULT = SPRING

        /** Animation appliquée en ce moment par le clavier. Posée au démarrage du processus et à chaque changement du réglage. */
        @Volatile
        var current: KeyPreviewAnimation = DEFAULT

        fun fromStorageKey(key: String?): KeyPreviewAnimation = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * Pose de la bulle d'agrandissement à un instant donné (logique pure, testée par `KeyPreviewPoseTest`). Le temps se compte depuis
 * l'appui ([sinceDownMs]) et depuis le début de la sortie ([sinceExitMs], négatif tant qu'elle n'a pas commencé). La sortie
 * commence [HOLD_MS] après le relâchement : la bulle reste affichée comme si la touche était encore appuyée.
 *
 * Les rebonds suivent le style d'animation du clavier ([MotionStyle.response] et l'étirement de Gelée), les durées aussi
 * ([MotionStyle.tuneDurationMs]) : plus vives en Vif, plus souples en Gelée.
 */
internal object KeyPreviewPose {

    /** Temps pendant lequel la bulle reste affichée après le relâchement, avant sa sortie (demande du 16/11/2026). */
    const val HOLD_MS = 150L

    /**
     * [scaleX], [scaleY] : taille autour du bas-centre de la bulle ; [offsetY] : décalage vertical en hauteurs de touche (positif
     * vers le bas) ; [alpha] : opacité ; [letterOffsetY] : décalage de la lettre seule ; [grow] (Goutte) : 0 = la touche, 1 = la bulle.
     */
    data class Pose(
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
        val offsetY: Float = 0f,
        val alpha: Float = 1f,
        val letterOffsetY: Float = 0f,
        val grow: Float = 1f,
    )

    val REST = Pose()

    /** Durée de l'entrée : au-delà, la bulle est posée et ne demande plus d'image tant que la touche reste appuyée. */
    fun enterDurationMs(style: MotionStyle): Long = style.tuneDurationMs(ENTER_MS)

    /** Durée de la sortie de [animation] (après [HOLD_MS]). */
    fun exitDurationMs(animation: KeyPreviewAnimation, style: MotionStyle): Long = style.tuneDurationMs(
        when (animation) {
            KeyPreviewAnimation.SPRING -> SPRING_EXIT_MS
            KeyPreviewAnimation.FLIGHT -> FLIGHT_EXIT_MS
            KeyPreviewAnimation.DROP -> DROP_EXIT_MS
            KeyPreviewAnimation.GLIDE -> GLIDE_EXIT_MS
        },
    )

    /**
     * Pose de la bulle, ou null une fois sa sortie terminée. [animated] faux (animations coupées dans les réglages système) : la
     * bulle est posée, immobile, et disparaît d'un coup à la fin de [HOLD_MS]. [fresh] faux (Glisse, bulle reprise par la touche
     * suivante) : pas de nouvelle entrée.
     */
    fun pose(
        animation: KeyPreviewAnimation,
        style: MotionStyle,
        sinceDownMs: Long,
        sinceExitMs: Long,
        animated: Boolean = true,
        fresh: Boolean = true,
    ): Pose? {
        val exiting = sinceExitMs >= 0
        if (!animated) return if (exiting) null else REST
        val u = if (exiting) sinceExitMs.toFloat() / exitDurationMs(animation, style) else 0f
        if (u >= 1f) return null
        return when (animation) {
            KeyPreviewAnimation.SPRING -> spring(style, sinceDownMs, u, fresh)
            KeyPreviewAnimation.GLIDE -> spring(style, sinceDownMs, 0f, fresh).copy(alpha = enterAlpha(sinceDownMs, fresh) * (1f - u))
            KeyPreviewAnimation.FLIGHT -> flight(style, sinceDownMs, u)
            KeyPreviewAnimation.DROP -> drop(style, sinceDownMs, u)
        }
    }

    /** Ressort : de [SPRING_START_SCALE] à sa taille en montant depuis la touche ; en sortie, se réduit et redescend dans la touche. */
    private fun spring(style: MotionStyle, sinceDownMs: Long, u: Float, fresh: Boolean): Pose {
        val r = if (fresh) style.response(sinceDownMs / MS_PER_S * SPRING_TIME_SCALE) else 1f
        var scale = SPRING_START_SCALE + (1f - SPRING_START_SCALE) * r
        val rise = if (fresh) easeOut(sinceDownMs.toFloat() / style.tuneDurationMs(RISE_MS)) else 1f
        var offset = (1f - rise) * RISE_KEYS
        var alpha = enterAlpha(sinceDownMs, fresh)
        if (u > 0f) {
            val q = u * u
            scale *= 1f - SPRING_EXIT_SHRINK * q
            offset += q * SPRING_EXIT_DROP_KEYS
            alpha *= 1f - q
        }
        return Pose(scale * style.stretchX(r), scale * style.stretchY(r), offset, alpha)
    }

    /** Envol : fondu et léger grossissement à l'entrée ; en sortie, monte d'une demi-touche, la lettre un peu plus vite. */
    private fun flight(style: MotionStyle, sinceDownMs: Long, u: Float): Pose {
        var scale = FLIGHT_START_SCALE + (1f - FLIGHT_START_SCALE) * easeOut(sinceDownMs.toFloat() / style.tuneDurationMs(FLIGHT_ENTER_MS))
        var alpha = clamp01(sinceDownMs.toFloat() / style.tuneDurationMs(FLIGHT_FADE_MS))
        if (u <= 0f) return Pose(scale, scale, 0f, alpha)
        val q = easeOut(u)
        scale *= 1f - FLIGHT_EXIT_SHRINK * q
        alpha *= 1f - u
        return Pose(scale, scale, -q * FLIGHT_RISE_KEYS, alpha, -q * FLIGHT_LETTER_RISE_KEYS)
    }

    /** Goutte : la touche s'étire jusqu'à la bulle avec le ressort du style ; en sortie, se rétracte dans la touche. */
    private fun drop(style: MotionStyle, sinceDownMs: Long, u: Float): Pose {
        var grow = min(style.response(sinceDownMs / MS_PER_S * DROP_TIME_SCALE), DROP_MAX_GROW)
        if (u > 0f) grow = min(grow, 1f) * (1f - u * u)
        return Pose(grow = max(grow, 0f))
    }

    private fun enterAlpha(sinceDownMs: Long, fresh: Boolean): Float = if (fresh) clamp01(sinceDownMs.toFloat() / FADE_IN_MS) else 1f

    fun easeOut(t: Float): Float {
        val k = 1f - clamp01(t)
        return 1f - k * k * k
    }

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    private const val MS_PER_S = 1000f
    private const val ENTER_MS = 260L
    private const val FADE_IN_MS = 35f
    private const val SPRING_START_SCALE = 0.55f

    /** Ressort deux fois plus rapide que celui du style : la bulle est posée en ~110 ms (Ressort). */
    private const val SPRING_TIME_SCALE = 2f
    private const val RISE_MS = 90L
    private const val RISE_KEYS = 0.55f
    private const val SPRING_EXIT_MS = 110L
    private const val SPRING_EXIT_SHRINK = 0.35f
    private const val SPRING_EXIT_DROP_KEYS = 0.5f
    private const val FLIGHT_START_SCALE = 0.9f
    private const val FLIGHT_ENTER_MS = 70L
    private const val FLIGHT_FADE_MS = 60L
    private const val FLIGHT_EXIT_MS = 170L
    private const val FLIGHT_EXIT_SHRINK = 0.08f
    private const val FLIGHT_RISE_KEYS = 0.55f
    private const val FLIGHT_LETTER_RISE_KEYS = 0.18f
    private const val DROP_TIME_SCALE = 1.6f
    private const val DROP_MAX_GROW = 1.15f
    private const val DROP_EXIT_MS = 120L
    private const val GLIDE_EXIT_MS = 100L
}
