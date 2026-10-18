package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.animation.OvershootInterpolator

/**
 * Icône qui pivote sur elle-même autour de son centre (le chevron du menu de gauche fait un demi-tour quand le menu
 * s'ouvre) sans faire tourner le fond du bouton. Teinte, état et opacité sont ceux de l'icône d'origine [inner].
 */
internal class RotatingDrawable(private val inner: Drawable) : Drawable(), Drawable.Callback {

    var angle = 0f
        private set

    private var animator: ValueAnimator? = null

    init {
        inner.callback = this
    }

    /** Fait pivoter l'icône jusqu'à [target] degrés ; [animate] faux (barre pas encore affichée) : saut direct. */
    fun rotateTo(target: Float, animate: Boolean) {
        animator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || angle == target) {
            angle = target
            invalidateSelf()
            return
        }
        animator = ValueAnimator.ofFloat(angle, target).apply {
            duration = DURATION_MS
            interpolator = OvershootInterpolator(OVERSHOOT_TENSION)
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        canvas.save()
        canvas.rotate(angle, bounds.exactCenterX(), bounds.exactCenterY())
        inner.draw(canvas)
        canvas.restore()
    }

    override fun onBoundsChange(bounds: Rect) {
        inner.bounds = bounds
    }

    override fun getIntrinsicWidth(): Int = inner.intrinsicWidth

    override fun getIntrinsicHeight(): Int = inner.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        inner.setTintList(tint)
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        inner.setTintMode(tintMode)
    }

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)

    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    private companion object {
        const val DURATION_MS = 320L
        const val OVERSHOOT_TENSION = 4f // même ressort que l'icône robot (RobotIconDrawable)
    }
}
