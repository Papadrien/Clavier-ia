package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * Icône vectorielle [iconRes] qui tourne sur elle-même avec un ressort (roue crantée du bouton Paramètres) : [spin] la
 * fait tourner jusqu'à [SpinIconPose.MAX_SPIN_S] ou jusqu'à ce qu'elle soit masquée (l'application s'ouvre et le clavier
 * disparaît), puis elle se pose sur un tour entier. Au repos : exactement l'icône d'origine. Animations système coupées :
 * aucune rotation. Teinte et opacité : celles du bouton.
 */
internal class SpinIconDrawable(
    context: Context,
    @DrawableRes iconRes: Int,
    private val sizePx: Int,
) : Drawable() {

    private val icon: Drawable = requireNotNull(ContextCompat.getDrawable(context, iconRes)).mutate()
    private val rotation = SpringValue()
    private var target = 0f
    private var spinSeconds = 0f
    private var spinning = false
    private var lastFrameNanos = 0L
    private var ticker: ValueAnimator? = null

    /** Appui sur le bouton : la roue se met à tourner (un nouvel appui pendant la rotation la prolonge). */
    fun spin() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        spinning = true
        spinSeconds = 0f
        ensureTicking()
    }

    /** Arrête l'animation (vue masquée ou détachée) et revient à la pose de repos. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        lastFrameNanos = 0L
        spinning = false
        spinSeconds = 0f
        target = 0f
        rotation.snap(0f)
        invalidateSelf()
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
        if (spinning) {
            spinSeconds += dt
            target = SpinIconPose.spinTarget(spinSeconds)
            if (spinSeconds >= SpinIconPose.MAX_SPIN_S) {
                spinning = false
                target = SpinIconPose.settleTarget(target)
            }
        }
        rotation.step(target, dt)
        if (!spinning && rotation.isSettledAngle(target)) {
            // Posée sur un tour entier : même dessin que le repos ; on repart de zéro pour ne pas cumuler les angles.
            target = 0f
            rotation.snap(0f)
            ticker?.cancel()
            ticker = null
            lastFrameNanos = 0L
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.rotate(rotation.value, b.exactCenterX(), b.exactCenterY())
        icon.setBounds(b.left, b.top, b.right, b.bottom)
        icon.draw(canvas)
        canvas.restore()
    }

    /** La rotation ne dure que tant que le bouton est visible : quand le clavier disparaît, la roue revient au repos. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) cancel()
        return changed
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        icon.setTintList(tint)
        invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) {
        icon.setTintMode(tintMode)
    }

    private companion object {
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
    }
}
