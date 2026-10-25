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
 * Icône Annuler (même tracé que `ic_undo.xml`, grille de 24) dessinée au Canvas pour l'animation « Rembobinage » :
 * [play] fait faire un tour complet à l'envers à l'icône, avec un ressort (voir [UndoIconPose]). Au repos : exactement
 * l'icône d'origine. Animations système coupées : aucune animation. Teinte et opacité : celles du bouton.
 */
internal class UndoIconDrawable(private val sizePx: Int) : Drawable() {

    private val rotation = SpringValue()
    private val scale = SpringValue(1f)
    private var turns = 0
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

    // Tracés de ic_undo.xml : la pointe de flèche et la courbe de retour.
    private val head: Path = PathParser.createPathFromPathData("M9,14 L4,9 L9,4")
    private val curve: Path = PathParser.createPathFromPathData("M4,9 H15 A5.5,5.5 0 0 1 15,20 H11")

    /** Appui sur le bouton : un tour de plus à l'envers, l'icône s'écrase puis rebondit. */
    fun play() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        turns += 1
        scale.snap(UndoIconPose.TAP_SQUASH)
        ensureTicking()
    }

    /** Le bouton redevient actif (une action est annulable) : l'icône « pop » une fois. */
    fun pop() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        scale.snap(UndoIconPose.ENABLE_SQUASH)
        ensureTicking()
    }

    /** Arrête l'animation en cours (vue détachée ou masquée) et revient à la pose de repos. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        lastFrameNanos = 0L
        turns = 0
        rotation.snap(0f)
        scale.snap(1f)
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
        val target = UndoIconPose.targetAngle(turns)
        rotation.step(target, dt)
        scale.step(1f, dt)
        if (rotation.isSettledAngle(target) && scale.isSettled(1f)) {
            // Posé : un nombre entier de tours donne la pose de repos ; on repart de zéro pour ne pas cumuler les angles.
            turns = 0
            rotation.snap(0f)
            scale.snap(1f)
            ticker?.cancel()
            ticker = null
            lastFrameNanos = 0L
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val color = tint?.defaultColor ?: Color.WHITE
        stroke.color = color
        stroke.alpha = Color.alpha(color) * alphaValue / 255

        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / GRID, b.height() / GRID)
        canvas.rotate(rotation.value, UndoIconPose.PIVOT, UndoIconPose.PIVOT)
        canvas.scale(scale.value, scale.value, UndoIconPose.PIVOT, UndoIconPose.PIVOT)
        canvas.drawPath(head, stroke)
        canvas.drawPath(curve, stroke)
        canvas.restore()
    }

    /** L'animation ne tourne que tant que le bouton est visible : elle s'arrête quand la barre est masquée ou détachée. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) cancel()
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
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
    }
}
