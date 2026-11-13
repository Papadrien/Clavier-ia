package fr.junade.taipo.backdrop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Cœurs (maquette validée le 13/11/2026, d'après une image de cœurs en néon) : bouquet de cœurs en néon sur leurs tiges, en bas
 * à droite, et quelques petits cœurs épars, dans la couleur d'accent avec un halo. La frappe fait balancer le bouquet et monter
 * les petits cœurs ; aucun effet sous la touche appuyée (décision du 13/11/2026 : seul le fond bouge).
 */
internal class HeartsPainter : BackdropPainter() {
    private class Balloon(val x: Float, val y: Float, val size: Float, val curve1: Float, val curve2: Float)
    private class Dust(val x: Float, val y: Float, val size: Float, val speed: Float)

    private val balloons = ArrayList<Balloon>()
    private val dust = ArrayList<Dust>()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun prepare(w: Float, h: Float, random: Random) {
        balloons.clear()
        BALLOONS.forEach { (x, y, size, c1, c2) ->
            balloons += Balloon(
                x = x + (random.nextFloat() - 0.5f) * 0.04f,
                y = y + (random.nextFloat() - 0.5f) * 0.04f,
                size = size,
                curve1 = c1,
                curve2 = c2,
            )
        }
        dust.clear()
        repeat(DUST) {
            dust += Dust(random.nextFloat() * 0.55f, random.nextFloat(), 0.03f + random.nextFloat() * 0.03f, 0.5f + random.nextFloat())
        }
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion) {
        val unit = min(w, h)
        val p = motion.phase
        // Lueur de l'accent en bas à droite, comme celle de l'image d'origine.
        halo.shader = RadialGradient(
            w * 0.78f,
            h * 0.72f,
            w * 0.7f,
            BackdropColors.withAlpha(colors.accent, if (colors.dark) 0.28f else 0.12f),
            BackdropColors.withAlpha(colors.accent, 0f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, halo)
        // En clair, trait et halo atténués : les touches restent lisibles.
        val strength = if (colors.dark) 1f else LIGHT_STRENGTH

        for (d in dust) {
            val y = (((d.y - p * DUST_DRIFT * d.speed) % 1f) + 1f) % 1f
            heart(d.x * w, y * h, d.size * unit)
            neon(canvas, colors, unit, 1f, strength * DUST_STRENGTH)
        }

        val rootX = w * 0.74f
        val rootY = h * 1.05f
        balloons.forEachIndexed { i, b ->
            val sway = sin(p * 0.9f + i * 1.7f) * unit * 0.025f
            val bob = cos(p * 0.7f + i) * unit * 0.012f
            val hx = b.x * w + sway
            val hy = b.y * h + bob
            val size = b.size * unit
            path.reset()
            path.moveTo(rootX + (i - 1.5f) * unit * 0.02f, rootY)
            path.cubicTo(rootX + b.curve1 * unit + sway * 0.4f, h * 0.85f, hx - b.curve2 * unit + sway, hy + size * 0.9f, hx, hy + size * 0.33f)
            neon(canvas, colors, unit, 1.6f, strength)
            heart(hx, hy, size)
            neon(canvas, colors, unit, 2f, strength)
        }
    }

    /** Pose dans [path] un cœur centré en ([x], [y]), de largeur [s]. */
    private fun heart(x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y + s * 0.35f)
        path.cubicTo(x - s * 0.05f, y + s * 0.3f, x - s * 0.55f, y + s * 0.05f, x - s * 0.5f, y - s * 0.22f)
        path.cubicTo(x - s * 0.45f, y - s * 0.5f, x - s * 0.08f, y - s * 0.52f, x, y - s * 0.25f)
        path.cubicTo(x + s * 0.08f, y - s * 0.52f, x + s * 0.45f, y - s * 0.5f, x + s * 0.5f, y - s * 0.22f)
        path.cubicTo(x + s * 0.55f, y + s * 0.05f, x + s * 0.05f, y + s * 0.3f, x, y + s * 0.35f)
        path.close()
    }

    /**
     * Trace [path] en néon : un halo large et flou dans l'accent, puis un trait fin et clair. Épaisseur et flou proportionnels au
     * petit côté du fond ([unit]) : même rendu quelle que soit la densité de l'écran. [weight] : épaisseur relative du trait.
     */
    private fun neon(canvas: Canvas, colors: BackdropColors, unit: Float, weight: Float, strength: Float) {
        val width = weight * unit * STROKE_FRACTION
        val blur = unit * BLUR_FRACTION * strength + 0.1f
        glowPaint.strokeWidth = width * 2.4f
        glowPaint.color = BackdropColors.withAlpha(colors.accent, 0.55f * strength)
        glowPaint.setShadowLayer(blur, 0f, 0f, colors.accent)
        canvas.drawPath(path, glowPaint)
        corePaint.strokeWidth = width
        corePaint.color = BackdropColors.withAlpha(colors.glow, min(1f, 0.4f + strength))
        corePaint.setShadowLayer(blur * 0.4f, 0f, 0f, colors.accent)
        canvas.drawPath(path, corePaint)
    }

    private companion object {
        /** Bouquet : position (fractions du fond), taille (fraction du petit côté), courbure des tiges. */
        val BALLOONS = listOf(
            listOf(0.66f, 0.36f, 0.20f, -0.10f, 0.06f),
            listOf(0.88f, 0.43f, 0.17f, 0.10f, -0.04f),
            listOf(0.55f, 0.55f, 0.14f, -0.16f, 0.04f),
            listOf(0.79f, 0.66f, 0.11f, 0.02f, 0.03f),
        )
        const val DUST = 8
        const val DUST_DRIFT = 0.02f
        const val DUST_STRENGTH = 0.35f
        const val LIGHT_STRENGTH = 0.45f

        /** Épaisseur du trait (poids 1) et rayon du halo, en fraction du petit côté du fond. */
        const val STROKE_FRACTION = 0.0045f
        const val BLUR_FRACTION = 0.05f
    }
}

/**
 * Fleurs (maquette validée le 13/11/2026, d'après un semis de pâquerettes) : pâquerettes à pétales blancs et cœur jaune sur un fond
 * teinté par l'accent (vert sauge avec l'accent vert). Peu de fleurs, pour garder les touches lisibles. La frappe fait tourner
 * doucement chaque fleur sur elle-même ; aucun effet sous la touche appuyée (seul le fond bouge).
 */
internal class FlowersPainter : BackdropPainter() {
    private class Flower(val x: Float, val y: Float, val radius: Float, val rotation: Float, val direction: Float)

    private val flowers = ArrayList<Flower>()
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CENTER }

    override fun prepare(w: Float, h: Float, random: Random) {
        flowers.clear()
        for (col in 0 until COLUMNS) {
            for (row in 0 until ROWS) {
                flowers += Flower(
                    x = (col + 0.5f + (random.nextFloat() - 0.5f) * 0.7f) / COLUMNS,
                    y = (row + 0.5f + (random.nextFloat() - 0.5f) * 0.6f) / ROWS,
                    radius = 0.045f + random.nextFloat() * 0.03f,
                    rotation = random.nextFloat() * 2f * PI.toFloat(),
                    direction = if (random.nextBoolean()) 1f else -1f,
                )
            }
        }
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion) {
        canvas.drawColor(BackdropColors.mix(colors.base, colors.accent, if (colors.dark) 0.32f else 0.42f))
        // En sombre, pétales légèrement gris pour ne pas éblouir.
        petalPaint.color = if (colors.dark) PETAL_DARK else PETAL_LIGHT
        val unit = min(w, h)
        for (f in flowers) {
            val r = f.radius * unit
            canvas.save()
            canvas.translate(f.x * w, f.y * h)
            canvas.rotate(Math.toDegrees((f.rotation + motion.phase * SPIN * f.direction).toDouble()).toFloat())
            repeat(PETALS) {
                canvas.drawOval(-r * 0.24f, -r * 1.08f, r * 0.24f, -r * 0.16f, petalPaint)
                canvas.rotate(360f / PETALS)
            }
            canvas.drawCircle(0f, 0f, r * 0.3f, centerPaint)
            canvas.restore()
        }
    }

    private companion object {
        const val COLUMNS = 5
        const val ROWS = 3
        const val PETALS = 8

        /** Rotation des fleurs par unité de phase (radians). */
        const val SPIN = 0.25f
        val PETAL_LIGHT = Color.rgb(241, 240, 242)
        val PETAL_DARK = Color.rgb(217, 214, 222)
        val CENTER = Color.rgb(255, 196, 0)
    }
}
