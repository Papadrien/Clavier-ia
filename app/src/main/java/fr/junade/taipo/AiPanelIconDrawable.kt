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
import androidx.core.graphics.PathParser

/**
 * Icône du bouton IA (`ic_ai_panel.xml`, grille de 24) dessinée au Canvas pour l'animation « Panneau qui se dessine » : panneau
 * ouvert, les étincelles se fondent en quatre cases (pose : [AiPanelIconPose]) ; à la fermeture elles reviennent. Passage en
 * ressort, comme les autres icônes de la barre. Au repos : exactement l'icône d'origine. Teinte et opacité : celles du bouton.
 */
internal class AiPanelIconDrawable(private val sizePx: Int) : Drawable() {

    private var active = false
    private val spring = SpringValue()
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

    // Tracés de ic_ai_panel.xml : grande et petite étincelle.
    private val bigSpark: Path = PathParser.createPathFromPathData(BIG_SPARK)
    private val smallSpark: Path = PathParser.createPathFromPathData(SMALL_SPARK)
    private val tileRect = RectF()

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
        val target = if (active) 1f else 0f
        spring.step(target, dt)
        invalidateSelf()
        if (spring.isSettled(target)) {
            spring.snap(target)
            cancel()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val color = tint?.defaultColor ?: Color.WHITE
        val a = Color.alpha(color) * alphaValue / OPAQUE
        stroke.color = color
        stroke.alpha = a
        fill.color = color

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)
        val w = spring.value

        val sparkAlpha = AiPanelIconPose.sparkAlpha(w)
        if (sparkAlpha > ALPHA_EPSILON) {
            fill.alpha = (a * sparkAlpha).toInt()
            val s = AiPanelIconPose.sparkScale(w)
            canvas.save()
            canvas.scale(s, s, AiPanelIconPose.CENTER, AiPanelIconPose.CENTER)
            canvas.drawPath(bigSpark, fill)
            canvas.drawPath(smallSpark, fill)
            canvas.restore()
        }
        AiPanelIconPose.TILES.forEachIndexed { i, tile ->
            val scale = AiPanelIconPose.tileScale(i, w)
            if (scale > SCALE_EPSILON) {
                val half = AiPanelIconPose.TILE_SIZE / 2f
                val cx = tile.x + half
                val cy = tile.y + half
                canvas.save()
                canvas.scale(scale, scale, cx, cy)
                tileRect.set(tile.x, tile.y, tile.x + AiPanelIconPose.TILE_SIZE, tile.y + AiPanelIconPose.TILE_SIZE)
                canvas.drawRoundRect(tileRect, AiPanelIconPose.TILE_RADIUS, AiPanelIconPose.TILE_RADIUS, stroke)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /** Bouton masqué : l'animation s'arrête et l'icône prend aussitôt sa pose d'arrivée (pas de pose figée entre deux). */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            cancel()
            spring.snap(if (active) 1f else 0f)
            invalidateSelf()
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
        const val OPAQUE = 255
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
        const val ALPHA_EPSILON = 0.005f
        const val SCALE_EPSILON = 0.01f
        const val BIG_SPARK = "M10,3 C10.5,7.8 13.2,10.5 18,11 C13.2,11.5 10.5,14.2 10,19 C9.5,14.2 6.8,11.5 2,11 C6.8,10.5 9.5,7.8 10,3 Z"
        const val SMALL_SPARK = "M18.5,14 C18.7,16.2 19.8,17.3 22,17.5 C19.8,17.7 18.7,18.8 18.5,21 C18.3,18.8 17.2,17.7 15,17.5 C17.2,17.3 18.3,16.2 18.5,14 Z"
    }
}
