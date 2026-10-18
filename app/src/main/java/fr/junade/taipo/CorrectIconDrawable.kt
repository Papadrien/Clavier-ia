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
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * Icône Correction (le A et la coche de `ic_spellcheck.xml`, grille de 24) dessinée au Canvas pour l'animation « Scanner » :
 * pendant le chargement ou la correction un faisceau balaie l'icône (elle est atténuée, éclairée là où passe le faisceau) ;
 * à la fin d'une correction réussie ([playDone]) la coche s'efface puis se retrace avec un rebond. Passage repos ↔ correction
 * avec un léger ressort. Teinte et opacité : celles posées par le bouton.
 */
internal class CorrectIconDrawable(private val sizePx: Int) : Drawable() {

    private var working = false
    private val workSpring = SpringValue()
    private var time = 0f
    private var doneTime = NO_DONE
    private var lastFrameNanos = 0L
    private var ticker: ValueAnimator? = null
    private var tint: ColorStateList? = null
    private var alphaValue = 255

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ICON_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Tracé de ic_spellcheck.xml.
    private val letter = Path().apply {
        moveTo(4f, 15f)
        lineTo(8.5f, 4f)
        lineTo(13f, 15f)
        moveTo(5.7f, 11f)
        lineTo(11.3f, 11f)
    }
    private val check = Path().apply {
        moveTo(11f, 17.5f)
        lineTo(14.5f, 21f)
        lineTo(20f, 14f)
    }
    private val checkMeasure = PathMeasure(check, false)
    private val checkLength = checkMeasure.length
    private val checkSegment = Path()

    /** Passe en « correction en cours » (ou revient au repos) ; [animate] faux ou animations coupées : pose directe. */
    fun setWorking(isWorking: Boolean, animate: Boolean) {
        if (working == isWorking) return
        working = isWorking
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            cancel()
            workSpring.snap(if (working) 1f else 0f)
            time = CorrectIconPose.BEAM_CENTERED_TIME_S // faisceau figé au milieu
            doneTime = NO_DONE
            invalidateSelf()
            return
        }
        ensureTicking()
    }

    /** Correction réussie : la coche se retrace avec un rebond. Sans effet si les animations système sont coupées. */
    fun playDone() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        doneTime = 0f
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
        workSpring.step(if (working) 1f else 0f, dt)
        if (doneTime >= 0f) {
            doneTime += dt
            if (doneTime >= CorrectIconPose.DONE_DURATION_S) doneTime = NO_DONE
        }
        invalidateSelf()
        if (!working && doneTime < 0f && workSpring.isSettled(0f)) cancel()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val color = tint?.defaultColor ?: Color.WHITE
        val a = Color.alpha(color) * alphaValue / 255
        stroke.color = color

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)

        val w = workSpring.value
        drawIcon(canvas, (a * CorrectIconPose.dimAlpha(w)).toInt())
        val bright = CorrectIconPose.brightAlpha(w)
        if (bright > ALPHA_EPSILON) {
            val x = CorrectIconPose.beamX(time)
            canvas.save()
            canvas.clipRect(x - CorrectIconPose.BEAM_HALF_WIDTH, 0f, x + CorrectIconPose.BEAM_HALF_WIDTH, GRID)
            drawIcon(canvas, (a * bright).toInt())
            canvas.restore()
            stroke.alpha = (a * CorrectIconPose.beamAlpha(w)).toInt()
            stroke.strokeWidth = BEAM_STROKE
            canvas.drawLine(x, BEAM_TOP, x, BEAM_BOTTOM, stroke)
            stroke.strokeWidth = ICON_STROKE
        }
        canvas.restore()
    }

    /** Le A et la coche (retracée et agrandie pendant l'animation de fin), à l'opacité [alpha]. */
    private fun drawIcon(canvas: Canvas, alpha: Int) {
        stroke.alpha = alpha
        canvas.drawPath(letter, stroke)
        val drawn = CorrectIconPose.checkDrawn(doneTime)
        if (drawn <= ALPHA_EPSILON) return
        val scale = CorrectIconPose.checkScale(doneTime)
        canvas.save()
        canvas.scale(scale, scale, CHECK_PIVOT_X, CHECK_PIVOT_Y)
        if (drawn >= 1f) {
            canvas.drawPath(check, stroke)
        } else {
            checkSegment.rewind()
            checkMeasure.getSegment(0f, drawn * checkLength, checkSegment, true)
            canvas.drawPath(checkSegment, stroke)
        }
        canvas.restore()
    }

    /** L'animation ne tourne que tant que le bouton est visible : elle s'arrête quand la barre est masquée ou détachée. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            cancel()
        } else if ((working || doneTime >= 0f) && ValueAnimator.areAnimatorsEnabled()) {
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
        const val ICON_STROKE = 2f
        const val BEAM_STROKE = 1.4f
        const val BEAM_TOP = 1.5f
        const val BEAM_BOTTOM = 22.5f
        const val CHECK_PIVOT_X = 15.5f
        const val CHECK_PIVOT_Y = 18f
        const val NO_DONE = -1f
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
        const val ALPHA_EPSILON = 0.005f
    }
}
