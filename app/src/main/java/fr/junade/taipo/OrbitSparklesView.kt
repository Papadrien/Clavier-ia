package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Trois étincelles qui tournent en orbite : la même animation que le bouton rond de la barre du prompt pendant le
 * chargement du modèle (voir [SendIconDrawable], positions et tailles de [SendIconPose]), seule, sans bulle. Elle
 * remplace la bulle « … » du chat pendant l'attente de la réponse du modèle. Dessinée sur la grille de 24, la vue est
 * carrée. L'animation ne tourne que tant que la vue est affichée ; animations coupées dans les réglages système :
 * étincelles figées.
 */
internal class OrbitSparklesView(context: Context, private val color: Int) : View(context) {

    private var time = 0f
    private var lastFrameNanos = 0L
    private var ticker: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = this@OrbitSparklesView.color
    }

    // Étincelle à quatre branches concaves, de rayon 1 (même forme que SendIconDrawable).
    private val sparkle = Path().apply {
        moveTo(0f, -1f)
        quadTo(0.12f, -0.12f, 1f, 0f)
        quadTo(0.12f, 0.12f, 0f, 1f)
        quadTo(-0.12f, 0.12f, -1f, 0f)
        quadTo(-0.12f, -0.12f, 0f, -1f)
        close()
    }

    override fun onDraw(canvas: Canvas) {
        val unit = minOf(width, height) / GRID
        val turns = if (ValueAnimator.areAnimatorsEnabled()) time / SendIconPose.ORBIT_PERIOD_S else 0f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(unit, unit)
        for (i in SendIconPose.SPARKLE_SCALES.indices) {
            val (x, y) = SendIconPose.sparklePosition(i, turns)
            val half = SendIconPose.SPARKLE_SIZE / 2f * SendIconPose.SPARKLE_SCALES[i]
            fill.alpha = (ALPHA_FULL * SendIconPose.SPARKLE_ALPHAS[i]).toInt()
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(turns * FULL_TURN)
            canvas.scale(half, half)
            canvas.drawPath(sparkle, fill)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun startTicking() {
        if (ticker != null || !ValueAnimator.areAnimatorsEnabled()) return
        lastFrameNanos = System.nanoTime()
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TICK_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                time += ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
                lastFrameNanos = now
                invalidate()
            }
            start()
        }
    }

    private fun stopTicking() {
        ticker?.cancel()
        ticker = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startTicking()
    }

    override fun onDetachedFromWindow() {
        stopTicking()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startTicking() else stopTicking()
    }

    private companion object {
        const val GRID = 24f
        const val FULL_TURN = 360f
        const val ALPHA_FULL = 255
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
    }
}
