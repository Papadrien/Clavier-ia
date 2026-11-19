package fr.junade.taipo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Particules laissées par la bulle des touches dans les styles du 19/11/2026 ([KeyPreviewAnimation.hasParticles]) : étincelles,
 * éclats de pixels, mini-cœurs, pétales, et les lueurs posées sur la touche. Logique pure (positions calculées depuis l'instant
 * de départ, sans état par image), testée en JVM (`KeyParticlesTest`) ; le dessin est fait par [KeyPreview].
 *
 * Les distances et vitesses sont exprimées à partir de [unit], la largeur d'une touche en pixels : l'effet garde les mêmes
 * proportions sur un téléphone, une tablette ou dans l'aperçu de la page « Apparence ». Au plus [MAX_PARTICLES] à la fois : en
 * frappe rapide, les plus anciennes disparaissent d'abord.
 */
internal class KeyParticles(private val random: Random = Random.Default) {

    enum class Kind {
        /** Trait lumineux qui suit sa trajectoire (Étincelles). */
        SPARK,

        /** Carré qui tombe (Pixels). */
        SQUARE,

        /** Petit carré qui clignote sur place (Pixels, à l'appui). */
        BLINK,

        /** Mini-cœur qui monte en se balançant (Bulle cœur). */
        HEART,

        /** Pétale qui tombe en tournoyant (Éclosion). */
        PETAL,

        /** Touche qui s'illumine puis s'éteint (Étincelles) : [Particle.x], [Particle.y] = coin haut-gauche, [Particle.size] × [Particle.size2]. */
        FLASH,

        /** Disque qui s'efface sur place (cœur de la fleur, Éclosion). */
        DOT,
    }

    /** Une particule : départ à [startAt] (ms) en ([x], [y]), vitesse en px/s, gravité en px/s², durée de vie [lifeMs]. */
    @Suppress("LongParameterList") // simple porteur de valeurs, presque toutes facultatives (nommées à l'appel)
    class Particle(
        val kind: Kind,
        val startAt: Long,
        val x: Float,
        val y: Float,
        val lifeMs: Long,
        val color: Int,
        val size: Float,
        val size2: Float = size,
        val vx: Float = 0f,
        val vy: Float = 0f,
        val gravity: Float = 0f,
        /** Angle de départ (radians) et rotation (radians/s) : pétales. */
        val angle: Float = 0f,
        val spin: Float = 0f,
        /** Amplitude (px) et phase du balancement horizontal : cœurs et pétales. */
        val sway: Float = 0f,
        val phase: Float = 0f,
    )

    /** Rectangle en pixels (touche ou bulle), sans dépendance à Android. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }

    /** Fleur de la bulle Éclosion : centre, distance de la base des pétales au centre, longueur et largeur d'un pétale. */
    data class Flower(val cx: Float, val cy: Float, val baseDistance: Float, val petalLength: Float, val petalWidth: Float)

    private val items = ArrayList<Particle>(MAX_PARTICLES)

    val particles: List<Particle> get() = items

    /** Vrai tant qu'une particule est encore visible (ou à venir) à l'instant [now] : la vue doit demander l'image suivante. */
    fun isRunning(now: Long): Boolean = items.any { now - it.startAt < it.lifeMs }

    /** Oublie les particules terminées à l'instant [now]. */
    fun prune(now: Long) {
        items.removeAll { now - it.startAt >= it.lifeMs }
    }

    fun clear() = items.clear()

    /** Avancement de [p] à [now] : 0 au départ, 1 à la fin ; négatif avant son départ (particule décalée). */
    fun progress(p: Particle, now: Long): Float = (now - p.startAt).toFloat() / p.lifeMs

    /** Abscisse de [p] à [now] (trajectoire, plus le balancement des cœurs et des pétales). */
    fun xAt(p: Particle, now: Long): Float {
        val t = seconds(p, now)
        val base = p.x + p.vx * t
        return when (p.kind) {
            Kind.HEART -> base + sin(p.phase + t * HEART_SWAY_RAD_S) * p.sway
            Kind.PETAL -> base + sin(t * PETAL_SWAY_RAD_S + p.angle) * p.sway * t
            else -> base
        }
    }

    /** Ordonnée de [p] à [now] (vitesse et gravité). */
    fun yAt(p: Particle, now: Long): Float {
        val t = seconds(p, now)
        return p.y + p.vy * t + 0.5f * p.gravity * t * t
    }

    /** Angle de [p] à [now] (radians). */
    fun angleAt(p: Particle, now: Long): Float = p.angle + p.spin * seconds(p, now)

    /** Opacité de [p] à [now] : pleine au départ, nulle à la fin (les clignotements alternent). */
    fun alphaAt(p: Particle, now: Long): Float {
        val t = progress(p, now).coerceIn(0f, 1f)
        if (p.kind == Kind.BLINK) return if ((t * BLINK_STEPS).toInt() % 2 == 0) 1f else BLINK_LOW
        if (p.kind == Kind.FLASH) return FLASH_ALPHA * (1f - t)
        return 1f - KeyPreviewPose.easeOut(t)
    }

    private fun seconds(p: Particle, now: Long): Float = ((now - p.startAt).coerceAtLeast(0L)) / MS_PER_S

    private fun add(p: Particle) {
        if (items.size >= MAX_PARTICLES) items.removeAt(0)
        items += p
    }

    /** Étincelles : gerbe de [SPARK_COUNT] traits depuis ([cx], [cy]), vers le haut surtout, qui retombent ; la touche s'illumine. */
    fun sparks(now: Long, cx: Float, cy: Float, unit: Float, accent: Int, light: Int, key: Box) {
        add(Particle(Kind.FLASH, now, key.left, key.top, FLASH_MS, accent, key.width, key.height))
        repeat(SPARK_COUNT) { i ->
            val a = (-PI / 2 + (random.nextFloat() - 0.5f) * PI * SPARK_SPREAD).toFloat()
            val speed = (SPARK_SPEED_MIN + random.nextFloat() * SPARK_SPEED_RANGE) * unit
            add(
                Particle(
                    Kind.SPARK, now, cx, cy,
                    lifeMs = SPARK_LIFE_MS + random.nextLong(SPARK_LIFE_RANGE_MS),
                    color = if (i % 2 == 0) accent else light,
                    size = unit * SPARK_WIDTH,
                    vx = cos(a) * speed,
                    vy = sin(a) * speed,
                    gravity = SPARK_GRAVITY * unit,
                ),
            )
        }
    }

    /** Pixels : la [bubble] se brise en [PIXEL_COLUMNS] × [PIXEL_ROWS] carrés qui s'écartent et tombent. */
    fun shatter(now: Long, bubble: Box, unit: Float, face: Int, accent: Int) {
        val cw = bubble.width / PIXEL_COLUMNS
        val ch = bubble.height / PIXEL_ROWS
        val cx = bubble.centerX
        val cy = bubble.centerY
        for (i in 0 until PIXEL_COLUMNS) {
            for (j in 0 until PIXEL_ROWS) {
                val x = bubble.left + i * cw + cw / 2f
                val y = bubble.top + j * ch + ch / 2f
                add(
                    Particle(
                        Kind.SQUARE, now, x, y,
                        lifeMs = PIXEL_LIFE_MS + random.nextLong(PIXEL_LIFE_RANGE_MS),
                        color = if ((i + j) % 3 == 0) accent else face,
                        size = min(cw, ch) * PIXEL_FILL,
                        vx = (x - cx) * PIXEL_SPREAD_X + (random.nextFloat() - 0.5f) * PIXEL_JITTER * unit,
                        vy = (y - cy) * PIXEL_SPREAD_Y - (PIXEL_LIFT + random.nextFloat() * PIXEL_LIFT_RANGE) * unit,
                        gravity = PIXEL_GRAVITY * unit,
                    ),
                )
            }
        }
    }

    /** Pixels, à l'appui : quelques pixels clignotent sur la [key]. */
    fun blink(now: Long, key: Box, unit: Float, color: Int) {
        repeat(BLINK_COUNT) { i ->
            add(
                Particle(
                    Kind.BLINK,
                    now + i * BLINK_STAGGER_MS,
                    key.left + random.nextFloat() * key.width,
                    key.top + random.nextFloat() * key.height,
                    lifeMs = BLINK_MS,
                    color = color,
                    size = unit * BLINK_SIZE,
                ),
            )
        }
    }

    /** Bulle cœur : traînée de [HEART_COUNT] mini-cœurs qui montent depuis la bulle ([cx], [cy], largeur [width]). */
    fun heartTrail(now: Long, cx: Float, cy: Float, width: Float, unit: Float, color: Int) {
        repeat(HEART_COUNT) { i ->
            add(
                Particle(
                    Kind.HEART, now + HEART_DELAY_MS + i * HEART_STAGGER_MS,
                    cx + (random.nextFloat() - 0.5f) * width * HEART_SPREAD, cy,
                    lifeMs = HEART_LIFE_MS, color = color, size = unit * HEART_SIZE,
                    vy = -(HEART_RISE + random.nextFloat() * HEART_RISE_RANGE) * unit,
                    sway = unit * HEART_SWAY, phase = random.nextFloat() * TWO_PI,
                ),
            )
        }
    }

    /**
     * Éclosion : les pétales de la [flower] (un par couleur de [colors]) se détachent et tombent en tournoyant ; le cœur de la
     * fleur ([core]) reste un instant sur la [key].
     */
    fun petalFall(now: Long, flower: Flower, unit: Float, colors: IntArray, key: Box, core: Int) {
        add(Particle(Kind.DOT, now, key.centerX, key.centerY, DOT_MS, core, unit * DOT_RADIUS))
        val petals = colors.size
        for (i in 0 until petals) {
            val a = (-PI / 2 + i * 2 * PI / petals).toFloat()
            add(
                Particle(
                    Kind.PETAL, now, flower.cx + cos(a) * flower.baseDistance, flower.cy + sin(a) * flower.baseDistance,
                    lifeMs = PETAL_LIFE_MS, color = colors[i], size = flower.petalLength, size2 = flower.petalWidth,
                    vx = cos(a) * PETAL_PUSH * unit,
                    vy = -random.nextFloat() * PETAL_LIFT * unit,
                    gravity = PETAL_GRAVITY * unit,
                    angle = (a + PI / 2).toFloat(),
                    spin = (random.nextFloat() - 0.5f) * PETAL_SPIN,
                    sway = PETAL_SWAY * unit,
                ),
            )
        }
    }

    companion object {
        /** Plafond de particules à l'écran (frappe rapide). */
        const val MAX_PARTICLES = 40
        const val SPARK_COUNT = 14
        const val PIXEL_COLUMNS = 5
        const val PIXEL_ROWS = 6

        private const val MS_PER_S = 1000f
        private const val TWO_PI = 6.2831855f

        // Valeurs en largeurs de touche (par seconde, par seconde²), reprises des maquettes du 19/11/2026.
        private const val SPARK_SPREAD = 1.6f
        private const val SPARK_SPEED_MIN = 4f
        private const val SPARK_SPEED_RANGE = 7.3f
        private const val SPARK_GRAVITY = 17f
        private const val SPARK_WIDTH = 0.075f
        private const val SPARK_LIFE_MS = 420L
        private const val SPARK_LIFE_RANGE_MS = 220L
        private const val FLASH_MS = 220L
        private const val FLASH_ALPHA = 0.7f

        private const val PIXEL_FILL = 0.86f
        private const val PIXEL_SPREAD_X = 3f
        private const val PIXEL_SPREAD_Y = 2.2f
        private const val PIXEL_JITTER = 2f
        private const val PIXEL_LIFT = 2f
        private const val PIXEL_LIFT_RANGE = 2.7f
        private const val PIXEL_GRAVITY = 21f
        private const val PIXEL_LIFE_MS = 520L
        private const val PIXEL_LIFE_RANGE_MS = 260L
        private const val BLINK_COUNT = 4
        private const val BLINK_STAGGER_MS = 40L
        private const val BLINK_MS = 260L
        private const val BLINK_SIZE = 0.16f
        private const val BLINK_STEPS = 4f
        private const val BLINK_LOW = 0.2f

        private const val HEART_COUNT = 4
        private const val HEART_DELAY_MS = 60L
        private const val HEART_STAGGER_MS = 70L
        private const val HEART_LIFE_MS = 650L
        private const val HEART_SIZE = 0.16f
        private const val HEART_SPREAD = 0.5f
        private const val HEART_RISE = 1.3f
        private const val HEART_RISE_RANGE = 1f
        private const val HEART_SWAY = 0.13f
        private const val HEART_SWAY_RAD_S = 6f

        private const val PETAL_LIFE_MS = 1000L
        private const val PETAL_PUSH = 1.3f
        private const val PETAL_LIFT = 0.7f
        private const val PETAL_GRAVITY = 5.3f
        private const val PETAL_SPIN = 6f
        private const val PETAL_SWAY = 0.6f
        private const val PETAL_SWAY_RAD_S = 5f
        private const val DOT_MS = 450L
        private const val DOT_RADIUS = 0.16f
    }
}
