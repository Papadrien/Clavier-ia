package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.widget.ImageButton

/**
 * Roue de chargement animée (arc qui tourne, trait arrondi), posée comme icône d'un bouton : même aspect que la roue du
 * bouton d'envoi du mode prompt (20 dp, couleur du texte). L'animation ne tourne que tant que le dessin est visible
 * (voir [setVisible]) : elle s'arrête quand la barre est masquée ou détachée.
 */
internal class LoadingSpinnerDrawable(context: Context) : Drawable(), Animatable {

    private val sizePx = context.dimen(R.dimen.taipo_bar_spinner_size).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = context.dimen(R.dimen.taipo_bar_spinner_stroke)
        color = context.themeColor(R.color.text_primary)
    }
    private val arc = RectF()
    private var rotation = 0f

    private val animator = ValueAnimator.ofFloat(0f, FULL_TURN).apply {
        duration = TURN_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val inset = paint.strokeWidth / 2f
        arc.set(area.left + inset, area.top + inset, area.right - inset, area.bottom - inset)
        val save = canvas.save()
        canvas.rotate(rotation, area.exactCenterX(), area.exactCenterY())
        canvas.drawArc(arc, 0f, SWEEP, false, paint)
        canvas.restoreToCount(save)
    }

    override fun start() {
        if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        animator.cancel()
    }

    override fun isRunning(): Boolean = animator.isRunning

    /** Le bouton qui porte le dessin le signale quand il est masqué ou détaché : l'animation s'arrête, sans fuite. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val FULL_TURN = 360f
        const val SWEEP = 270f
        const val TURN_MS = 900L
    }
}

/**
 * Icône d'un bouton rond de la barre : la roue [spinner] pendant le [loading], l'icône micro sinon. Ne touche au bouton
 * que si l'icône change (appelé à chaque rendu du bouton).
 */
internal fun ImageButton.showMicOrSpinner(loading: Boolean, spinner: LoadingSpinnerDrawable) {
    if (loading) {
        if (drawable !== spinner) setImageDrawable(spinner)
        spinner.start()
    } else {
        spinner.stop()
        if (drawable === spinner) setImageResource(R.drawable.ic_mic)
    }
}
