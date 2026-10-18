package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * Icône Smart Clipboard (le presse-papiers de `ic_paste.xml`, grille de 24) dessinée au Canvas pour l'animation
 * « Étincelles » : panneau ouvert, le presse-papiers s'incline et trois étincelles jaillissent en tournant puis scintillent ;
 * à la fermeture elles rentrent et il se redresse. Passage avec un léger ressort. Au repos : exactement l'icône d'origine.
 * Les étincelles débordent de la grille de 24 (le bouton est plus grand que l'icône). Teinte et opacité : celles du bouton.
 */
internal class ClipboardIconDrawable(private val sizePx: Int) : Drawable() {

    private var active = false
    private val spring = SpringValue()
    private var time = 0f
    private var lastFrameNanos = 0L
    private var ticker: ValueAnimator? = null
    private var tint: ColorStateList? = null
    private var alphaValue = 255

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // Tracé de ic_paste.xml : la feuille (ouverte en haut, sous la pince) et la pince.
    private val body = Path().apply {
        moveTo(9f, 4f)
        lineTo(7f, 4f)
        arcTo(RectF(5f, 4f, 9f, 8f), 270f, -90f)
        lineTo(5f, 19f)
        arcTo(RectF(5f, 17f, 9f, 21f), 180f, -90f)
        lineTo(17f, 21f)
        arcTo(RectF(15f, 17f, 19f, 21f), 90f, -90f)
        lineTo(19f, 6f)
        arcTo(RectF(15f, 4f, 19f, 8f), 0f, -90f)
        lineTo(15f, 4f)
    }
    private val clip = Path().apply { addRoundRect(RectF(9f, 2.5f, 15f, 6f), 1f, 1f, Path.Direction.CW) }

    // Étincelle à quatre branches de rayon 1, centrée sur l'origine.
    private val spark = Path().apply {
        moveTo(0f, -1f)
        quadTo(0.12f, -0.12f, 1f, 0f)
        quadTo(0.12f, 0.12f, 0f, 1f)
        quadTo(-0.12f, 0.12f, -1f, 0f)
        quadTo(-0.12f, -0.12f, 0f, -1f)
        close()
    }

    /** Panneau ouvert ou fermé ; [animate] faux (barre pas encore affichée) ou animations coupées : pose directe. */
    fun setActive(isActive: Boolean, animate: Boolean) {
        if (active == isActive) return
        active = isActive
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            cancel()
            spring.snap(if (active) 1f else 0f)
            invalidateSelf()
            return
        }
        ensureTicking()
    }

    /** Arrête l'animation en cours (vue détachée) en gardant la pose actuelle. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        lastFrameNanos = 0L
    }

    private fun ensureTicking() {
        if (ticker != null) return
        lastFrameNanos = System.nanoTime()
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TICK_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { onFrame() }
            start()
        }
    }

    private fun onFrame() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastFrameNanos = now
        time += dt
        spring.step(if (active) 1f else 0f, dt)
        invalidateSelf()
        if (!active && spring.isSettled(0f)) cancel() // les étincelles scintillent tant que le panneau est ouvert
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val color = tint?.defaultColor ?: Color.WHITE
        val a = Color.alpha(color) * alphaValue / 255
        stroke.color = color
        stroke.alpha = a
        fill.color = color

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)

        val w = spring.value
        canvas.save()
        canvas.rotate(ClipboardIconPose.tilt(w), ClipboardIconPose.PIVOT_X, ClipboardIconPose.PIVOT_Y)
        canvas.drawPath(body, stroke)
        canvas.drawPath(clip, stroke)
        canvas.restore()

        val sparkAlpha = ClipboardIconPose.sparkAlpha(w)
        if (sparkAlpha > ALPHA_EPSILON) {
            fill.alpha = (a * sparkAlpha).toInt()
            ClipboardIconPose.SPARKS.forEachIndexed { i, s ->
                val scale = ClipboardIconPose.sparkScale(i, w, time)
                if (scale > SCALE_EPSILON) {
                    canvas.save()
                    canvas.translate(s.x, s.y)
                    canvas.rotate(ClipboardIconPose.sparkRotation(i, w, time))
                    canvas.scale(scale, scale)
                    canvas.drawPath(spark, fill)
                    canvas.restore()
                }
            }
        }
        canvas.restore()
    }

    /** L'animation ne tourne que tant que le bouton est visible : elle s'arrête quand la barre est masquée ou détachée. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            cancel()
        } else if (active && ValueAnimator.areAnimatorsEnabled()) {
            ensureTicking()
        }
        return changed
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) = Unit

    private companion object {
        const val GRID = 24f
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
        const val ALPHA_EPSILON = 0.005f
        const val SCALE_EPSILON = 0.01f
    }
}
