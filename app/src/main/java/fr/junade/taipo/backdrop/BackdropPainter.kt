package fr.junade.taipo.backdrop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Couleurs d'un fond : celle du clavier ([base]), la couleur d'accent, et le thème (sombre ou clair). */
class BackdropColors(val base: Int, val accent: Int, val dark: Boolean) {
    /** Accent éclairci : reflets et arêtes lumineuses. */
    val glow: Int = mix(accent, Color.WHITE, if (dark) 0.35f else 0.1f)

    companion object {
        fun mix(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k).roundToInt()
            return Color.argb(255, ch(16), ch(8), ch(0))
        }

        fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)

        /** Même couleur, teinte tournée de [degrees] (irisation des bulles, second ton des rubans). */
        fun hueShift(color: Int, degrees: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[0] = (hsv[0] + degrees + 360f) % 360f
            return Color.HSVToColor(hsv)
        }

        /**
         * Second ton d'accent : [color] dont la teinte est tournée de [degrees], écart élargi là où l'œil distingue mal les
         * teintes voisines ([widenedShift]). Revue du 10/11/2026 : sur le bleu vif, le second ton (±28°) restait du bleu.
         */
        fun secondTone(color: Int, degrees: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            return hueShift(color, widenedShift(hsv[0], degrees))
        }

        /** Bande des bleus (teinte HSV) où deux teintes proches se confondent à l'œil. */
        const val BLUE_BAND_START = 190f
        const val BLUE_BAND_END = 255f

        /** Facteur appliqué au décalage de teinte dans la bande des bleus. */
        const val BLUE_BAND_WIDENING = 2.2f

        /**
         * Décalage de teinte à appliquer à une couleur de teinte [hue] pour un second ton voulu à [degrees] : multiplié par
         * [BLUE_BAND_WIDENING] dans la bande des bleus (215° + 28° y reste bleu, 215° + 62° devient violet), inchangé ailleurs.
         * Logique pure, testée en JVM (`BackdropColorsTest`).
         */
        fun widenedShift(hue: Float, degrees: Float): Float =
            if (hue in BLUE_BAND_START..BLUE_BAND_END) degrees * BLUE_BAND_WIDENING else degrees
    }
}

/**
 * Dessin d'un motif de fond (maquettes du 10/11/2026). [prepare] tire la disposition des formes à partir de [seed] (une seule
 * fois par taille et par tirage) ; [draw] peint une image, à la [BackdropMotion.phase] et avec les touches récentes de [motion].
 */
abstract class BackdropPainter {
    private var preparedWidth = -1
    private var preparedHeight = -1
    private var preparedSeed = Long.MIN_VALUE

    fun drawFrame(canvas: Canvas, width: Int, height: Int, seed: Long, colors: BackdropColors, motion: BackdropMotion) {
        if (width <= 0 || height <= 0) return
        if (width != preparedWidth || height != preparedHeight || seed != preparedSeed) {
            prepare(width.toFloat(), height.toFloat(), Random(seed))
            preparedWidth = width
            preparedHeight = height
            preparedSeed = seed
        }
        canvas.drawColor(colors.base)
        draw(canvas, width.toFloat(), height.toFloat(), colors, motion)
    }

    protected abstract fun prepare(w: Float, h: Float, random: Random)

    protected abstract fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion)

    companion object {
        fun of(backdrop: KeyboardBackdrop): BackdropPainter? = when (backdrop) {
            KeyboardBackdrop.PLAIN -> null
            KeyboardBackdrop.RIBBONS -> RibbonsPainter()
            KeyboardBackdrop.BUBBLES -> BubblesPainter()
            KeyboardBackdrop.CRYSTAL -> CrystalPainter()
            KeyboardBackdrop.WAVES -> WavesPainter()
            KeyboardBackdrop.HEARTS -> HeartsPainter()
            KeyboardBackdrop.FLOWERS -> FlowersPainter()
        }
    }
}

/** Rubans : six rubans satinés en deux tons, reflet qui court le long de chacun ; la frappe fait tourner et glisser l'ensemble. */
internal class RibbonsPainter : BackdropPainter() {
    private var cxFraction = 0f
    private var cyFraction = 0f
    private var baseRotation = 0f
    private var secondHue = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun prepare(w: Float, h: Float, random: Random) {
        cxFraction = 0.2f + random.nextFloat() * 0.3f
        cyFraction = 1f + random.nextFloat() * 0.15f
        baseRotation = -0.5f + (random.nextFloat() - 0.5f) * 0.5f
        secondHue = if (random.nextBoolean()) 28f else -28f
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion) {
        val p = motion.phase * RIBBON_SPEED
        val cx = w * cxFraction + sin(p * 0.21f) * w * 0.12f
        val cy = h * cyFraction + cos(p * 0.17f) * h * 0.08f
        val second = BackdropColors.secondTone(colors.accent, secondHue)
        halo.shader = RadialGradient(
            cx,
            cy - h * 0.4f,
            w * 0.9f,
            BackdropColors.withAlpha(colors.accent, if (colors.dark) 0.22f else 0.14f),
            BackdropColors.withAlpha(colors.accent, 0f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, halo)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(Math.toDegrees((baseRotation + 0.35f * sin(p * 0.12f)).toDouble()).toFloat())
        for (i in RIBBONS - 1 downTo 0) {
            val tone = if (i % 2 == 1) second else colors.accent
            val size = h * (0.42f + i * 0.27f) * (1f + 0.015f * motion.energy)
            val bw = size * 1.9f
            val bh = size * 1.15f
            val lw = h * (0.085f + 0.02f * (i % 3))
            val sweep = 0.13f + 0.74f * (((p * 0.35f + i * 0.37f) % 1f + 1f) % 1f)
            val dim = if (colors.dark) 0.45f else 0.35f
            paint.shader = LinearGradient(
                -bw / 2f,
                -bh,
                bw / 2f,
                bh * 0.3f,
                intArrayOf(
                    BackdropColors.mix(colors.base, tone, if (colors.dark) 0.25f else 0.2f),
                    BackdropColors.mix(colors.base, tone, dim),
                    BackdropColors.mix(tone, Color.WHITE, if (colors.dark) 0.55f else 0.6f),
                    BackdropColors.mix(colors.base, tone, dim),
                    BackdropColors.mix(colors.base, tone, if (colors.dark) 0.12f else 0.1f),
                ),
                floatArrayOf(0f, sweep - 0.12f, sweep, sweep + 0.12f, 1f),
                Shader.TileMode.CLAMP,
            )
            paint.strokeWidth = lw
            paint.setShadowLayer(14f, 0f, 6f, BackdropColors.withAlpha(Color.BLACK, if (colors.dark) 0.6f else 0.18f))
            rect.set(-bw / 2f, -bh, bw / 2f, bh)
            canvas.drawRoundRect(rect, bh, bh, paint)
            outline.strokeWidth = 1.2f
            outline.color = BackdropColors.withAlpha(BackdropColors.mix(tone, Color.WHITE, 0.6f), if (colors.dark) 0.45f else 0.6f)
            rect.inset(-lw / 2f, -lw / 2f)
            canvas.drawRoundRect(rect, bh + lw / 2f, bh + lw / 2f, outline)
        }
        canvas.restore()
    }

    private companion object {
        const val RIBBONS = 6

        /** Vitesse de l'animation (défilement des réglages et frappe) : réduite de 80 % (revue du 10/11/2026, trop rapide). */
        const val RIBBON_SPEED = 0.2f
    }
}

/** Bulles : bulles liquides irisées en orbite (la frappe les fait avancer) et petite bulle qui monte de chaque touche. */
internal class BubblesPainter : BackdropPainter() {
    private class Blob(val x: Float, val y: Float, val rx: Float, val ry: Float, val rotation: Float, val phase: Float)

    private val blobs = ArrayList<Blob>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()

    override fun prepare(w: Float, h: Float, random: Random) {
        blobs.clear()
        repeat(BLOBS) {
            blobs += Blob(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                rx = (0.08f + random.nextFloat() * 0.16f) * w,
                ry = (0.05f + random.nextFloat() * 0.1f) * w,
                rotation = random.nextFloat() * PI.toFloat(),
                phase = random.nextFloat() * 6f,
            )
        }
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion) {
        val p = motion.phase
        for (b in blobs) {
            val wobble = 1f + 0.06f * motion.energy * sin(p * 2f + b.phase)
            val dx = cos(b.phase + p * 0.35f) * w * 0.1f
            val dy = sin(b.phase * 1.3f + p * 0.28f) * h * 0.12f
            val rotation = b.rotation + 0.25f * sin(p * 0.2f + b.phase)
            val alpha = if (colors.dark) 0.9f else 0.75f
            drawBlob(canvas, colors, b.x + dx, b.y + dy, b.rx * wobble, b.ry / wobble, rotation, alpha)
        }
        for (hit in motion.hits) {
            val t = hit.age
            drawBlob(canvas, colors, hit.x, hit.y - t * 70f, 6f + t * 10f, 5f + t * 8f, 0f, max(0f, 1f - t / 1.1f) * 0.9f)
        }
    }

    @Suppress("LongParameterList")
    private fun drawBlob(canvas: Canvas, colors: BackdropColors, x: Float, y: Float, rx: Float, ry: Float, rotation: Float, alpha: Float) {
        if (alpha <= 0f || rx <= 0f || ry <= 0f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(rotation.toDouble()).toFloat())
        fill.shader = RadialGradient(
            -rx * 0.3f,
            -ry * 0.3f,
            max(rx, ry),
            intArrayOf(
                BackdropColors.mix(colors.glow, Color.WHITE, 0.6f),
                BackdropColors.withAlpha(colors.accent, 0.9f),
                BackdropColors.withAlpha(BackdropColors.secondTone(colors.accent, 40f), 0.85f),
                BackdropColors.withAlpha(BackdropColors.secondTone(colors.accent, -40f), 0.7f),
            ),
            floatArrayOf(0f, 0.45f, 0.8f, 1f),
            Shader.TileMode.CLAMP,
        )
        fill.alpha = (alpha * 255).roundToInt()
        oval.set(-rx, -ry, rx, ry)
        canvas.drawOval(oval, fill)
        ring.strokeWidth = 2f
        val rim = if (colors.dark) Color.WHITE else BackdropColors.mix(colors.accent, Color.WHITE, 0.5f)
        ring.color = BackdropColors.withAlpha(rim, 0.85f * alpha)
        canvas.drawOval(oval, ring)
        ring.strokeWidth = 1f
        ring.color = BackdropColors.withAlpha(BackdropColors.hueShift(colors.accent, 90f), 0.6f * alpha)
        oval.set(-rx * 0.92f, -ry * 0.9f, rx * 0.92f, ry * 0.9f)
        canvas.drawOval(oval, ring)
        canvas.restore()
    }

    private companion object {
        const val BLOBS = 7
    }
}

/** Cristal : facettes et arêtes lumineuses ; la frappe fait glisser les sommets, une onde de lumière part de chaque touche. */
internal class CrystalPainter : BackdropPainter() {
    private val jitter = ArrayList<FloatArray>() // jx, jy, phase par sommet
    private val flips = ArrayList<Boolean>()
    private val shades = ArrayList<Float>()
    private val points = ArrayList<FloatArray>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    override fun prepare(w: Float, h: Float, random: Random) {
        jitter.clear()
        flips.clear()
        shades.clear()
        points.clear()
        repeat((COLS + 1) * (ROWS + 1)) {
            jitter += floatArrayOf((random.nextFloat() - 0.5f) * 0.8f, (random.nextFloat() - 0.5f) * 0.8f, random.nextFloat() * 6.28f)
            points += FloatArray(2)
        }
        repeat(COLS * ROWS) { flips += random.nextBoolean() }
        repeat(COLS * ROWS * 2) { shades += random.nextFloat() }
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion) {
        val p = motion.phase
        for (j in 0..ROWS) {
            for (i in 0..COLS) {
                val index = j * (COLS + 1) + i
                val edgePoint = i == 0 || j == 0 || i == COLS || j == ROWS
                val (jx, jy, ph) = jitter[index]
                val mx = if (edgePoint) 0f else jx + 0.28f * sin(p * 0.3f + ph)
                val my = if (edgePoint) 0f else jy + 0.28f * cos(p * 0.26f + ph)
                points[index][0] = (i + mx) * w / COLS
                points[index][1] = (j + my) * h / ROWS
            }
        }
        var triangle = 0
        for (j in 0 until ROWS) {
            for (i in 0 until COLS) {
                val a = points[j * (COLS + 1) + i]
                val b = points[j * (COLS + 1) + i + 1]
                val c = points[(j + 1) * (COLS + 1) + i + 1]
                val d = points[(j + 1) * (COLS + 1) + i]
                val pair = if (flips[j * COLS + i]) listOf(arrayOf(a, b, c), arrayOf(a, c, d)) else listOf(arrayOf(a, b, d), arrayOf(b, c, d))
                for (t in pair) drawFacet(canvas, t, shades[triangle++], colors, motion)
            }
        }
    }

    private fun drawFacet(canvas: Canvas, t: Array<FloatArray>, shade: Float, colors: BackdropColors, motion: BackdropMotion) {
        val cx = (t[0][0] + t[1][0] + t[2][0]) / 3f
        val cy = (t[0][1] + t[1][1] + t[2][1]) / 3f
        val glow = glowAt(cx, cy, motion)
        fill.color = if (colors.dark) {
            BackdropColors.mix(BackdropColors.mix(Color.rgb(8, 8, 12), colors.accent, 0.05f + shade * 0.18f), colors.accent, glow * 0.35f)
        } else {
            BackdropColors.mix(BackdropColors.mix(Color.rgb(250, 250, 252), colors.accent, 0.04f + shade * 0.14f), colors.accent, glow * 0.25f)
        }
        path.rewind()
        path.moveTo(t[0][0], t[0][1])
        path.lineTo(t[1][0], t[1][1])
        path.lineTo(t[2][0], t[2][1])
        path.close()
        canvas.drawPath(path, fill)
        for (e in 0 until 3) {
            val a = t[e]
            val b = t[(e + 1) % 3]
            val g = glowAt((a[0] + b[0]) / 2f, (a[1] + b[1]) / 2f, motion)
            val base = 0.18f + 0.12f * sin(a[0] * 0.05f + b[1] * 0.07f)
            if (colors.dark || g > 0.05f) {
                // Halo des arêtes : un trait large et pâle sous le trait net (plus léger qu'une ombre portée à chaque image).
                edge.strokeWidth = 3f + g * 5f
                edge.color = BackdropColors.withAlpha(colors.accent, (if (colors.dark) 0.12f else 0f) + g * 0.35f)
                canvas.drawLine(a[0], a[1], b[0], b[1], edge)
            }
            edge.strokeWidth = 1f + g * 1.5f
            edge.color = BackdropColors.withAlpha(if (g > 0.05f) BackdropColors.mix(colors.accent, colors.glow, g) else colors.accent, min(1f, base + g))
            canvas.drawLine(a[0], a[1], b[0], b[1], edge)
        }
    }

    private fun glowAt(x: Float, y: Float, motion: BackdropMotion): Float {
        var g = 0f
        for (hit in motion.hits) {
            val ring = hit.age * RING_SPEED
            val d = hypot(x - hit.x, y - hit.y)
            val k = (d - ring) / RING_WIDTH
            g += exp(-k * k) * max(0f, 1f - hit.age / BackdropMotion.HIT_LIFETIME_S)
        }
        return min(1f, g)
    }

    private companion object {
        const val COLS = 7
        const val ROWS = 5
        const val RING_SPEED = 120f
        const val RING_WIDTH = 22f
    }
}

/** Ondes : onze lignes sinueuses espacées, qui s'écoulent tant qu'on tape. */
internal class WavesPainter : BackdropPainter() {
    private class Line(val amplitude: Float, val phase: Float, val white: Boolean, val tone: Float, val alpha: Float, val width: Float)

    private val lines = ArrayList<Line>()
    private var k1 = 0f
    private var k2 = 0f
    private var tilt = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun prepare(w: Float, h: Float, random: Random) {
        k1 = 0.02f + random.nextFloat() * 0.02f
        k2 = 0.05f + random.nextFloat() * 0.03f
        tilt = (random.nextFloat() - 0.5f) * 0.6f
        lines.clear()
        repeat(LINES) {
            val white = random.nextFloat() > 0.9f
            lines += Line(
                amplitude = 7f + random.nextFloat() * 8f,
                phase = random.nextFloat() * 6f,
                white = white,
                tone = random.nextFloat(),
                alpha = if (white) 0.55f else 0.3f + random.nextFloat() * 0.35f,
                width = WAVE_LINE_SCALE * (if (white) 1f else 1.2f + random.nextFloat() * 1.6f),
            )
        }
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion) {
        val p = motion.phase
        val drift = sin(p * 0.15f) * h * 0.06f
        lines.forEachIndexed { i, line ->
            val y0 = (i + 0.5f) * h / LINES
            val amp = line.amplitude * (1f + 0.5f * motion.energy)
            path.rewind()
            var x = -STEP
            while (x <= w + STEP) {
                val y = y0 + tilt * (x - w / 2f) * 0.4f + drift + amp * sin(x * k1 + line.phase + p) +
                    amp * 0.45f * sin(x * k2 - line.phase * 0.7f - p * 1.6f)
                if (x == -STEP) path.moveTo(x, y) else path.lineTo(x, y)
                x += STEP
            }
            val color = when {
                line.white && colors.dark -> Color.WHITE
                line.white -> BackdropColors.mix(colors.accent, Color.rgb(20, 18, 28), 0.2f)
                else -> BackdropColors.mix(colors.accent, colors.glow, line.tone)
            }
            paint.color = BackdropColors.withAlpha(color, line.alpha)
            paint.strokeWidth = line.width
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        /** Épaisseur des lignes triplée (revue du 10/11/2026 : trop fines). */
        const val WAVE_LINE_SCALE = 3f

        const val LINES = 11
        const val STEP = 6f
    }
}
