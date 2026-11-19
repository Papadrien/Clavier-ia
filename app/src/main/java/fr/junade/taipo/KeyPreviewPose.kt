package fr.junade.taipo

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Forme de l'animation de la bulle d'agrandissement des touches (maquettes du 16/11/2026). Elle n'a pas de réglage propre : elle
 * vient du style d'animation choisi ([MotionStyle.bubble]). La bulle reste au moins [KeyPreviewPose.MIN_SHOW_MS] à l'écran.
 */
enum class KeyPreviewAnimation {
    /** Sort de la touche en grandissant avec un rebond, y rentre en se réduisant. */
    SPRING,

    /** Entrée discrète ; au relâchement, la lettre s'envole vers le texte en s'effaçant. */
    FLIGHT,

    /** La touche s'étire vers le haut comme une goutte et devient la bulle, puis se rétracte. */
    DROP,

    /** Une seule bulle qui suit la frappe : elle glisse de touche en touche au lieu de s'éteindre et se rallumer. */
    GLIDE,

    /** Étincelles (maquettes du 19/11/2026) : jaillit avec un rebond vif ; au relâchement, éclate en gerbe d'étincelles. */
    SPARKS,

    /** Pixels : apparaît d'un coup ; au relâchement, se brise en carrés qui tombent (quelques pixels clignotent à l'appui). */
    PIXELS,

    /** Bulle cœur : la bulle est un cœur ; au relâchement, il s'envole en se balançant, suivi de mini-cœurs. */
    HEART,

    /** Éclosion : cinq pétales s'ouvrent l'un après l'autre autour de la lettre ; au relâchement, ils tombent en tournoyant. */
    BLOOM,
    ;

    /** Au relâchement, la bulle laisse des particules ([KeyParticles]) : étincelles, éclats, cœurs ou pétales. */
    val hasParticles: Boolean get() = this == SPARKS || this == PIXELS || this == HEART || this == BLOOM
}

/**
 * Pose de la bulle d'agrandissement à un instant donné (logique pure, testée par `KeyPreviewPoseTest`). Le temps se compte depuis
 * l'appui ([sinceDownMs]) et depuis le début de la sortie ([sinceExitMs], négatif tant qu'elle n'a pas commencé). La sortie
 * commence au relâchement, mais jamais moins de [MIN_SHOW_MS] après l'appui : même un appui très bref laisse voir la bulle.
 *
 * Les rebonds suivent le style d'animation du clavier ([MotionStyle.response] et l'étirement de Gelée), les durées aussi
 * ([MotionStyle.tuneDurationMs]) : plus vives en Vif, plus souples en Gelée.
 */
internal object KeyPreviewPose {

    /** Durée minimale d'affichage de la bulle, comptée depuis l'appui, avant sa sortie (demande du 16/11/2026). */
    const val MIN_SHOW_MS = 150L

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
        /** Décalage horizontal en largeurs de touche (positif vers la droite) : balancement de la bulle cœur. */
        val offsetX: Float = 0f,
    )

    val REST = Pose()

    /** Durée de l'entrée : au-delà, la bulle est posée et ne demande plus d'image tant que la touche reste appuyée. */
    fun enterDurationMs(style: MotionStyle): Long = when (style.bubble) {
        // Ressorts propres aux bulles du 19/11/2026, plus longs à se poser que l'entrée habituelle.
        KeyPreviewAnimation.SPARKS -> SPARKS_ENTER_MS
        KeyPreviewAnimation.HEART -> HEART_ENTER_MS
        KeyPreviewAnimation.BLOOM -> BLOOM_ENTER_MS
        else -> style.tuneDurationMs(ENTER_MS)
    }

    /**
     * Durée de la sortie de [animation] (après le relâchement, ou [MIN_SHOW_MS] après l'appui). Étincelles, Pixels et Éclosion
     * sortent presque aussitôt : la suite est faite de particules ([KeyParticles]), qui ont leur propre durée.
     */
    fun exitDurationMs(animation: KeyPreviewAnimation, style: MotionStyle): Long = when (animation) {
        KeyPreviewAnimation.SPRING -> style.tuneDurationMs(SPRING_EXIT_MS)
        KeyPreviewAnimation.FLIGHT -> style.tuneDurationMs(FLIGHT_EXIT_MS)
        KeyPreviewAnimation.DROP -> style.tuneDurationMs(DROP_EXIT_MS)
        KeyPreviewAnimation.GLIDE -> style.tuneDurationMs(GLIDE_EXIT_MS)
        KeyPreviewAnimation.SPARKS -> SPARKS_EXIT_MS
        KeyPreviewAnimation.PIXELS -> PIXELS_EXIT_MS
        KeyPreviewAnimation.HEART -> HEART_EXIT_MS
        KeyPreviewAnimation.BLOOM -> BLOOM_EXIT_MS
    }

    /**
     * Pose de la bulle, ou null une fois sa sortie terminée. [animated] faux (animations coupées dans les réglages système) : la
     * bulle est posée, immobile, et disparaît d'un coup quand sa sortie commence. [fresh] faux (Glisse, bulle reprise par la touche
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
            KeyPreviewAnimation.SPARKS -> sparks(sinceDownMs, u)
            KeyPreviewAnimation.PIXELS -> pixels(sinceDownMs, u)
            KeyPreviewAnimation.HEART -> heart(sinceDownMs, u)
            KeyPreviewAnimation.BLOOM -> bloom(sinceDownMs, u)
        }
    }

    /**
     * Éclosion : ouverture (0 fermé, 1 ouvert, rebond compris) du pétale [index] (0 à 4) à [sinceDownMs] ; chaque pétale part
     * [BLOOM_PETAL_STAGGER_MS] après le précédent. [animated] faux : pétales ouverts.
     */
    fun petalOpen(sinceDownMs: Long, index: Int, animated: Boolean = true): Float {
        if (!animated) return 1f
        return springAt(BLOOM_PETAL_OMEGA, BLOOM_PETAL_ZETA, (sinceDownMs - index * BLOOM_PETAL_STAGGER_MS) / MS_PER_S)
    }

    /** Étincelles : jaillit avec un rebond vif ; en sortie, gonfle et s'efface en un éclair (les étincelles prennent le relais). */
    private fun sparks(sinceDownMs: Long, u: Float): Pose {
        val r = springAt(SPARKS_OMEGA, SPARKS_ZETA, sinceDownMs / MS_PER_S)
        var scale = SPRING_START_SCALE + (1f - SPRING_START_SCALE) * r
        var alpha = enterAlpha(sinceDownMs, fresh = true)
        if (u > 0f) {
            scale *= 1f + SPARKS_EXIT_GROW * u
            alpha *= 1f - u
        }
        return Pose(scale, scale, 0f, alpha)
    }

    /** Pixels : apparaît d'un coup (à peine plus petite la première trame) ; en sortie, laisse la place aux éclats. */
    private fun pixels(sinceDownMs: Long, u: Float): Pose {
        if (u > 0f) return Pose(alpha = 0f)
        val scale = if (sinceDownMs < PIXELS_SNAP_MS) PIXELS_START_SCALE else 1f
        return Pose(scale, scale)
    }

    /** Bulle cœur : grandit avec un rebond ; en sortie, s'envole de [HEART_RISE_KEYS] touches en se balançant et s'efface. */
    private fun heart(sinceDownMs: Long, u: Float): Pose {
        val r = springAt(HEART_OMEGA, HEART_ZETA, sinceDownMs / MS_PER_S)
        var scale = SPRING_START_SCALE + (1f - SPRING_START_SCALE) * r
        val alpha = enterAlpha(sinceDownMs, fresh = true)
        if (u <= 0f) return Pose(scale, scale, 0f, alpha)
        val e = easeOut(u)
        scale *= 1f - HEART_EXIT_SHRINK * u
        val sway = sin(u * HEART_SWAY_RAD) * HEART_SWAY_KEYS * (1f - u)
        return Pose(scale, scale, -e * HEART_RISE_KEYS, alpha * (1f - e), offsetX = sway)
    }

    /** Éclosion : le cœur de la fleur (la lettre) grandit avec un rebond ; les pétales suivent [petalOpen]. Sortie : les pétales tombent. */
    private fun bloom(sinceDownMs: Long, u: Float): Pose {
        if (u > 0f) return Pose(alpha = 0f)
        val c = springAt(BLOOM_CORE_OMEGA, BLOOM_CORE_ZETA, sinceDownMs / MS_PER_S)
        return Pose(c, c, 0f, enterAlpha(sinceDownMs, fresh = true))
    }

    /** Position d'un ressort (pulsation [omega], amortissement [zeta] < 1) lâché de 0 vers 1, à [seconds] ; 0 avant le départ. */
    private fun springAt(omega: Float, zeta: Float, seconds: Float): Float {
        if (seconds <= 0f) return 0f
        val omegaD = omega * sqrt(1f - zeta * zeta)
        val decay = zeta * omega
        return 1f - exp(-decay * seconds) * (cos(omegaD * seconds) + decay / omegaD * sin(omegaD * seconds))
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

    // Étincelles, Pixels, Bulle cœur, Éclosion (maquettes du 19/11/2026).
    private const val SPARKS_OMEGA = 34f
    private const val SPARKS_ZETA = 0.42f
    private const val SPARKS_ENTER_MS = 300L
    private const val SPARKS_EXIT_MS = 90L
    private const val SPARKS_EXIT_GROW = 0.3f
    private const val PIXELS_SNAP_MS = 30L
    private const val PIXELS_START_SCALE = 0.9f
    private const val PIXELS_EXIT_MS = 40L
    private const val HEART_OMEGA = 24f
    private const val HEART_ZETA = 0.4f
    private const val HEART_ENTER_MS = 400L
    private const val HEART_EXIT_MS = 600L
    private const val HEART_EXIT_SHRINK = 0.6f
    private const val HEART_RISE_KEYS = 2.2f
    private const val HEART_SWAY_KEYS = 0.15f
    private const val HEART_SWAY_RAD = 8.4f
    private const val BLOOM_CORE_OMEGA = 30f
    private const val BLOOM_CORE_ZETA = 0.6f
    private const val BLOOM_PETAL_OMEGA = 26f
    private const val BLOOM_PETAL_ZETA = 0.5f
    private const val BLOOM_PETAL_STAGGER_MS = 25L
    private const val BLOOM_ENTER_MS = 400L
    private const val BLOOM_EXIT_MS = 40L
}
