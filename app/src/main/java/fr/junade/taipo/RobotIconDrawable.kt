package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.OvershootInterpolator

/**
 * Icône robot du bouton Action IA, dessinée au Canvas (même tracé que `ic_robot.xml`, grille de 24) pour pouvoir animer
 * l'antenne, les yeux et la bouche quand le bouton passe de « repos » à « actif » (fond violet), et inversement.
 * Durée [DURATION_MS], léger ressort à l'aller comme au retour. Teinte et opacité : celles posées par le bouton.
 */
internal class RobotIconDrawable(private val sizePx: Int) : Drawable() {

    /** Avancement affiché : 0 = repos, 1 = actif ; dépasse un peu 0..1 pendant le ressort. */
    var progress = 0f
        private set

    private var animator: ValueAnimator? = null
    private var targetProgress = 0f
    private var tint: ColorStateList? = null
    private var alphaValue = 255

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val head = RectF(5f, 8f, 19f, 20f)
    private val eyeClip = RectF()

    /** Passe à [active] ; [animate] faux (barre pas encore affichée) ou animations coupées : saut direct. */
    fun setActive(active: Boolean, animate: Boolean) {
        val target = if (active) 1f else 0f
        targetProgress = target
        animator?.cancel()
        animator = null
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || progress == target) {
            progress = target
            invalidateSelf()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = DURATION_MS
            interpolator = OvershootInterpolator(OVERSHOOT_TENSION)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    /**
     * Arrête l'animation en cours (vue détachée) et prend aussitôt la pose d'arrivée : sinon une animation interrompue
     * laisse le robot figé entre repos et actif, et rien ne la relance au retour (l'état n'a pas changé).
     */
    fun cancel() {
        animator?.cancel()
        animator = null
        if (progress != targetProgress) {
            progress = targetProgress
            invalidateSelf()
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

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)

        canvas.drawRoundRect(head, 2f, 2f, stroke)
        canvas.drawLine(12f, RobotIconPose.ANTENNA_BASE_Y, 12f, RobotIconPose.antennaTopY(progress), stroke)
        canvas.drawLine(3f, 12.5f, 3f, 16.5f, stroke)
        canvas.drawLine(21f, 12.5f, 21f, 16.5f, stroke)

        val shift = RobotIconPose.eyeShiftY(progress)
        val squint = RobotIconPose.eyeSquint(progress)
        for (cx in EYE_CENTERS_X) {
            val cy = EYE_CENTER_Y + shift
            canvas.save()
            val bottom = cy + EYE_RADIUS - 2f * EYE_RADIUS * squint
            eyeClip.set(cx - EYE_RADIUS - 1f, cy - EYE_RADIUS - 1f, cx + EYE_RADIUS + 1f, bottom)
            canvas.clipRect(eyeClip)
            canvas.drawCircle(cx, cy, EYE_RADIUS, fill)
            canvas.restore()
        }

        val mouthAlpha = RobotIconPose.mouthAlpha(progress)
        if (mouthAlpha > 0f) {
            stroke.alpha = (a * mouthAlpha).toInt()
            stroke.strokeWidth = MOUTH_STROKE
            val half = MOUTH_HALF_WIDTH * RobotIconPose.mouthScaleX(progress)
            canvas.drawLine(12f - half, MOUTH_Y, 12f + half, MOUTH_Y, stroke)
            stroke.strokeWidth = 2f
        }
        canvas.restore()
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
        const val DURATION_MS = 300L
        const val OVERSHOOT_TENSION = 4f
        const val GRID = 24f
        const val EYE_RADIUS = 1.4f
        const val EYE_CENTER_Y = 13.5f
        val EYE_CENTERS_X = floatArrayOf(9f, 15f)
        const val MOUTH_Y = 16.6f
        const val MOUTH_HALF_WIDTH = 2.4f
        const val MOUTH_STROKE = 1.6f
    }
}
