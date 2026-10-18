package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.roundToInt

/**
 * Icône du bouton Transcription IA, dessinée au Canvas sur une grille de 24 : trois barres verticales en onde. Au repos l'onde est
 * fixe ; à l'enregistrement les barres montent et descendent selon le niveau sonore réel ([setLevel]) ; pendant le
 * traitement elles se couchent en trois lignes qu'un reflet de teinte [accentColor] balaie. Les passages d'un état à
 * l'autre se font avec un léger ressort. Teinte et opacité de base : celles posées par le bouton.
 */
internal class WaveIconDrawable(private val sizePx: Int, private val accentColor: Int = 0) : Drawable() {

    private var mode = WaveIconMode.IDLE
    private val loadSpring = SpringValue()
    private val listenSpring = SpringValue()
    private var targetLevel = 0f
    private var shownLevel = 0f
    private var time = 0f
    private var lastFrameNanos = 0L
    private var ticker: ValueAnimator? = null
    private var tint: ColorStateList? = null
    private var alphaValue = 255

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Passe à [newMode] ; [animate] faux (barre pas encore affichée) ou animations coupées : pose directe, sans ressort. */
    fun setMode(newMode: WaveIconMode, animate: Boolean) {
        if (mode == newMode) return
        mode = newMode
        if (newMode != WaveIconMode.LISTENING) targetLevel = 0f
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
        if (mode != WaveIconMode.LISTENING) return
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

    private fun loadTarget() = if (mode == WaveIconMode.WORKING) 1f else 0f

    private fun listenTarget() = if (mode == WaveIconMode.LISTENING) 1f else 0f

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
        shownLevel = WaveIconPose.smooth(shownLevel, targetLevel, dt)
        invalidateSelf()
        val idle = mode == WaveIconMode.IDLE && shownLevel < LEVEL_EPSILON
        if (idle && loadSpring.isSettled(0f) && listenSpring.isSettled(0f)) cancel()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val base = tint?.defaultColor ?: Color.WHITE
        val alpha = Color.alpha(base) * alphaValue / OPAQUE
        val listen = listenSpring.value.coerceIn(0f, 1f)
        val load = loadSpring.value.coerceIn(0f, 1f)

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)
        for (i in 0 until WaveIconPose.BAR_COUNT) {
            val r = WaveIconPose.bar(i, listenSpring.value, loadSpring.value, shownLevel, time)
            val toAccent = (1f - listen) * load * WaveIconPose.sweep(i, time) * SWEEP_STRENGTH
            fill.color = blend(base, accentColor, if (accentColor == 0) 0f else toAccent)
            fill.alpha = alpha
            val radius = minOf(r.width, r.height) / 2f
            canvas.drawRoundRect(r.x, r.y, r.x + r.width, r.y + r.height, radius, radius, fill)
        }
        canvas.restore()
    }

    /** Mélange [from] vers [to] (alpha de [from] conservé) : [weight] = 0 donne [from], 1 donne [to]. */
    private fun blend(from: Int, to: Int, weight: Float): Int {
        val w = weight.coerceIn(0f, 1f)
        if (w == 0f) return from
        fun mixed(a: Int, b: Int) = (a + (b - a) * w).roundToInt()
        return Color.argb(
            Color.alpha(from),
            mixed(Color.red(from), Color.red(to)),
            mixed(Color.green(from), Color.green(to)),
            mixed(Color.blue(from), Color.blue(to)),
        )
    }

    /** L'animation ne tourne que tant que le bouton est visible : elle s'arrête quand la barre est masquée ou détachée. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            cancel()
        } else if (mode != WaveIconMode.IDLE && ValueAnimator.areAnimatorsEnabled()) {
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
        const val SWEEP_STRENGTH = 0.9f
        const val OPAQUE = 255
    }
}
