package fr.junade.taipo

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Choreographer
import fr.junade.taipo.backdrop.BackdropColors
import fr.junade.taipo.backdrop.BackdropMotion
import fr.junade.taipo.backdrop.BackdropPainter
import fr.junade.taipo.backdrop.KeyboardBackdrop

/**
 * Fond unique de tout le clavier : barre du haut, touches et panneaux (emoji, Smart Clipboard) sont
 * transparents et laissent voir ce fond, posé sur la vue racine de l'IME. Les animations
 * d'arrière-plan se dessinent ici, une seule fois, et passent donc derrière la barre et le clavier
 * sans raccord visible.
 *
 * Clavier flottant : [cornerRadius] arrondit les coins de la carte, et le contour ([getOutline]) donne son ombre.
 *
 * Fond du clavier (page « Thème ») : uni, ou un motif généré ([setBackdrop]) dans la couleur d'accent. Animé ([animated]), le
 * motif ne bouge que pendant la frappe ([onKeyPress]) : une image par trame tant que le mouvement dure ([BackdropMotion]),
 * rien à l'arrêt. Animations Android coupées : le motif reste fixe.
 */
class KeyboardBackgroundDrawable(backgroundColor: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val baseColor = backgroundColor
    private val rect = RectF()
    private val clip = Path()

    private var painter: BackdropPainter? = null
    private var backdrop = KeyboardBackdrop.PLAIN
    private var seed = 0L
    private var colors: BackdropColors? = null
    private val motion = BackdropMotion()

    /** Le motif s'anime pendant la frappe (réglage « Animer le fond pendant la frappe »). */
    var animated = true

    private var lastFrameNanos = 0L
    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

    /** Rayon des coins (px) : 0 pour le clavier ancré en bas, arrondi pour le clavier flottant. */
    var cornerRadius = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    /** Motif à dessiner (ou fond uni), tirage de sa disposition et couleur d'accent. Sans effet si rien ne change. */
    fun setBackdrop(value: KeyboardBackdrop, seed: Long, accent: Int, dark: Boolean) {
        val newColors = BackdropColors(baseColor, accent, dark)
        val sameColors = colors?.let { it.accent == accent && it.dark == dark } == true
        if (value == backdrop && seed == this.seed && sameColors) return
        if (value != backdrop) painter = BackdropPainter.of(value)
        backdrop = value
        this.seed = seed
        colors = newColors
        invalidateSelf()
    }

    /** Une touche vient d'être appuyée en ([x], [y]), coordonnées de ce fond : le motif dérive et réagit autour d'elle. */
    fun onKeyPress(x: Float, y: Float) {
        if (painter == null || !animated || !ValueAnimator.areAnimatorsEnabled()) return
        motion.press(x - bounds.left, y - bounds.top)
        if (!frameScheduled) {
            frameScheduled = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame(nowNanos: Long) {
        frameScheduled = false
        val dt = if (lastFrameNanos == 0L) FIRST_FRAME_S else ((nowNanos - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastFrameNanos = nowNanos
        motion.step(dt)
        invalidateSelf()
        if (motion.isRunning && callback != null) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun draw(canvas: Canvas) {
        val currentPainter = painter
        val currentColors = colors
        if (currentPainter == null || currentColors == null) {
            if (cornerRadius <= 0f) {
                canvas.drawRect(bounds, paint)
                return
            }
            rect.set(bounds)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            return
        }
        canvas.save()
        if (cornerRadius > 0f) {
            rect.set(bounds)
            clip.rewind()
            clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clip)
        } else {
            canvas.clipRect(bounds)
        }
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        currentPainter.drawFrame(canvas, bounds.width(), bounds.height(), seed, currentColors, motion)
        canvas.restore()
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadius)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (cornerRadius > 0f) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE

    private companion object {
        const val FIRST_FRAME_S = 1f / 60f
        const val MAX_FRAME_S = 0.033f
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
