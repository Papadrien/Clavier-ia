package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * Icône micro du bouton Vocal, dessinée au Canvas (même tracé que `ic_mic.xml`, grille de 24). Seule la capsule s'anime :
 * en chargement (ou transcription) son contour se dessine puis s'efface en boucle ; à l'écoute une jauge la remplit et la
 * vide selon le niveau sonore réel ([setLevel]). Le pied (U, tige, base) ne change jamais. Les passages d'un état à l'autre
 * se font avec un léger ressort. Teinte et opacité : celles posées par le bouton.
 */
internal class MicIconDrawable(private val sizePx: Int) : Drawable() {

    private var mode = MicIconMode.IDLE
    private val loadSpring = SpringValue()
    private val listenSpring = SpringValue()
    private var targetLevel = 0f
    private var shownLevel = 0f
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

    // Tracé de ic_mic.xml : capsule (de y = 3 à 14), U, tige et base.
    private val capsule = Path().apply {
        moveTo(12f, 3f)
        arcTo(RectF(9f, 3f, 15f, 9f), -90f, 90f)
        lineTo(15f, 11f)
        arcTo(RectF(9f, 8f, 15f, 14f), 0f, 180f)
        lineTo(9f, 6f)
        arcTo(RectF(9f, 3f, 15f, 9f), 180f, 90f)
        close()
    }
    private val foot = Path().apply {
        moveTo(5.5f, 11f)
        arcTo(RectF(5.5f, 4.5f, 18.5f, 17.5f), 180f, -180f)
        moveTo(12f, 17.5f)
        lineTo(12f, 21f)
        moveTo(9f, 21f)
        lineTo(15f, 21f)
    }
    private val measure = PathMeasure(capsule, true)
    private val capsuleLength = measure.length
    private val segment = Path()
    private val wave = Path()

    /** Passe à [newMode] ; [animate] faux (barre pas encore affichée) ou animations coupées : pose directe, sans ressort. */
    fun setMode(newMode: MicIconMode, animate: Boolean) {
        if (mode == newMode) return
        mode = newMode
        if (newMode != MicIconMode.LISTENING) targetLevel = 0f
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            cancel()
            loadSpring.snap(loadTarget())
            listenSpring.snap(listenTarget())
            shownLevel = targetLevel
            invalidateSelf()
            return
        }
        ensureTicking()
    }

    /** Niveau sonore (0 à 1) du dernier bloc audio ; ignoré hors écoute. À appeler sur le thread principal. */
    fun setLevel(level: Float) {
        if (mode != MicIconMode.LISTENING) return
        targetLevel = level.coerceIn(0f, 1f)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            shownLevel = targetLevel
            invalidateSelf()
        } else {
            ensureTicking()
        }
    }

    /** Arrête l'animation en cours (vue détachée) en gardant la pose actuelle. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        lastFrameNanos = 0L
    }

    private fun loadTarget() = if (mode == MicIconMode.LOADING) 1f else 0f

    private fun listenTarget() = if (mode == MicIconMode.LISTENING) 1f else 0f

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
        loadSpring.step(loadTarget(), dt)
        listenSpring.step(listenTarget(), dt)
        shownLevel = MicIconPose.smooth(shownLevel, targetLevel, dt)
        invalidateSelf()
        val idle = mode == MicIconMode.IDLE && shownLevel < LEVEL_EPSILON
        if (idle && loadSpring.isSettled(0f) && listenSpring.isSettled(0f)) cancel()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val color = tint?.defaultColor ?: Color.WHITE
        val a = Color.alpha(color) * alphaValue / 255
        stroke.color = color
        stroke.alpha = a
        fill.color = color
        fill.alpha = a * GAUGE_ALPHA / 255

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)

        canvas.drawPath(foot, stroke) // le pied ne bouge jamais

        val level = MicIconPose.gaugeFill(listenSpring.value, shownLevel)
        if (level > FILL_EPSILON) {
            wave.rewind()
            wave.moveTo(WAVE_LEFT, MicIconPose.surfaceY(level, WAVE_LEFT, time))
            var x = WAVE_LEFT + WAVE_STEP
            while (x <= WAVE_RIGHT) {
                wave.lineTo(x, MicIconPose.surfaceY(level, x, time))
                x += WAVE_STEP
            }
            wave.lineTo(WAVE_RIGHT, WAVE_BOTTOM)
            wave.lineTo(WAVE_LEFT, WAVE_BOTTOM)
            wave.close()
            canvas.save()
            canvas.clipPath(capsule)
            canvas.drawPath(wave, fill)
            canvas.restore()
        }

        val (start, end) = MicIconPose.traceWindow(time, loadSpring.value)
        if (start <= 0f && end >= 1f) {
            canvas.drawPath(capsule, stroke)
        } else if (end - start > MIN_TRACE) {
            segment.rewind()
            measure.getSegment(start * capsuleLength, end * capsuleLength, segment, true)
            canvas.drawPath(segment, stroke)
        }
        canvas.restore()
    }

    /** L'animation ne tourne que tant que le bouton est visible : elle s'arrête quand la barre est masquée ou détachée. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            cancel()
        } else if (mode != MicIconMode.IDLE && ValueAnimator.areAnimatorsEnabled()) {
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
        const val LEVEL_EPSILON = 0.005f
        const val FILL_EPSILON = 0.01f
        const val GAUGE_ALPHA = 242 // 0,95 sur 255
        const val MIN_TRACE = 0.003f
        const val WAVE_LEFT = 8f
        const val WAVE_RIGHT = 16f
        const val WAVE_STEP = 0.5f
        const val WAVE_BOTTOM = 15f
    }
}
