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
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser

/**
 * Icône du bouton afficher/masquer le chat (même tracé que `ic_chat.xml`, grille de 24) : la bulle ne bouge pas, seule
 * l'étincelle s'anime, avec un ressort.
 *
 * - [setShown] vrai (chat ouvert) : l'étincelle apparaît en tournant (elle part de zéro, un demi-tour en arrière) ;
 * - [setShown] faux (chat fermé) : elle tourne d'un quart de tour en rétrécissant à 70 % (voir [ChatIconPose]).
 *
 * Animations coupées dans les réglages système, ou [animate] faux : pose directe. Teinte et opacité : celles du bouton.
 */
internal class ChatIconDrawable(private val sizePx: Int) : Drawable() {

    private var shown = false
    private val scale = SpringValue(ChatIconPose.restScale(false))
    private val rotation = SpringValue(ChatIconPose.restRotation(false))
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

    // Tracés de ic_chat.xml.
    private val bubble: Path = PathParser.createPathFromPathData(BUBBLE)
    private val sparkle: Path = PathParser.createPathFromPathData(SPARKLE)

    /** Affiche l'état [newShown] ; [animate] faux (bouton pas encore posé) ou animations coupées : pose directe. */
    fun setShown(newShown: Boolean, animate: Boolean) {
        if (shown == newShown) return
        shown = newShown
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            cancel()
            scale.snap(ChatIconPose.restScale(newShown))
            rotation.snap(ChatIconPose.restRotation(newShown))
            invalidateSelf()
            return
        }
        if (newShown) {
            // Apparition : l'étincelle repart de rien, un demi-tour en arrière, et se pose en ressort.
            scale.snap(0f)
            rotation.snap(ChatIconPose.APPEAR_FROM_ROTATION)
        }
        ensureTicking()
    }

    /** Arrête l'animation en cours (vue détachée ou masquée) en gardant la pose actuelle. */
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
        scale.step(ChatIconPose.restScale(shown), dt)
        rotation.step(ChatIconPose.restRotation(shown), dt)
        invalidateSelf()
        if (scale.isSettled(ChatIconPose.restScale(shown)) && rotation.isSettledAngle(ChatIconPose.restRotation(shown))) {
            scale.snap(ChatIconPose.restScale(shown))
            rotation.snap(ChatIconPose.restRotation(shown))
            cancel()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val color = tint?.defaultColor ?: Color.WHITE
        val a = Color.alpha(color) * alphaValue / 255
        stroke.color = color
        stroke.alpha = a
        fill.color = color
        fill.alpha = a

        val save = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)
        canvas.drawPath(bubble, stroke)
        val s = scale.value
        if (s > 0f) {
            canvas.save()
            canvas.rotate(rotation.value, SPARKLE_X, SPARKLE_Y)
            canvas.scale(s, s, SPARKLE_X, SPARKLE_Y)
            canvas.drawPath(sparkle, fill)
            canvas.restore()
        }
        canvas.restoreToCount(save)
    }

    /** L'animation ne tourne que tant que le bouton est visible ; au retour, elle reprend où elle s'était arrêtée. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) cancel() else if (ValueAnimator.areAnimatorsEnabled()) ensureTicking()
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
        const val SPARKLE_X = 12f
        const val SPARKLE_Y = 10f
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
        const val BUBBLE = "M20,15 a2,2 0 0 1 -2,2 H8 L4,21 V5 a2,2 0 0 1 2,-2 H18 a2,2 0 0 1 2,2 Z"
        const val SPARKLE =
            "M12,5.8 C12.3,8.3 13.7,9.7 16.2,10 C13.7,10.3 12.3,11.7 12,14.2 C11.7,11.7 10.3,10.3 7.8,10 C10.3,9.7 11.7,8.3 12,5.8 Z"
    }
}
