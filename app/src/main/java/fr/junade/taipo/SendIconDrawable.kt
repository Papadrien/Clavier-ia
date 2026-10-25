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
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

/**
 * Icône du bouton rond de la barre du prompt (proposition A « Orbite »), dessinée au Canvas sur la grille de 24 :
 *
 * - [SendIconMode.LOADING] : trois étincelles tournent en orbite (chargement du modèle, arrêt en cours) ;
 * - passage à l'icône suivante : l'orbite se replie vers le centre, puis l'icône remonte d'en bas avec un ressort ;
 * - [SendIconMode.SEND] : flèche vers le haut ; [launch] la fait décoller (elle s'étire et s'envole) pendant qu'une
 *   onde part du bouton, puis l'icône suivante (flèche, ou carré stop pendant la génération) remonte avec un ressort ;
 * - [SendIconMode.STOP] : carré arrondi.
 *
 * Le dessin est coupé au cercle de la face du bouton ([faceRadiusPx]) pour que la flèche sorte « du bouton ». Teinte et
 * opacité : celles posées par le bouton. Animations coupées dans les réglages système : poses directes, orbite figée.
 */
internal class SendIconDrawable(private val sizePx: Int, private val faceRadiusPx: Float) : Drawable() {

    private var mode = SendIconMode.LOADING
    private val orbit = ProgressValue(1f)
    private val arrowSpring = SpringValue()
    private val stopSpring = SpringValue()
    private var enterDelay = 0f
    private var launching = false
    private var launchTime = 0f
    private var ringTime = -1f
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
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_STROKE
    }

    // Flèche vers le haut (grille de 24) : tige et pointe.
    private val arrow = Path().apply {
        moveTo(12f, 19f)
        lineTo(12f, 5f)
        moveTo(5f, 12f)
        lineTo(12f, 5f)
        lineTo(19f, 12f)
    }
    private val stopSquare = RectF(6f, 6f, 18f, 18f)

    // Étincelle à quatre branches concaves, de rayon 1 (mise à l'échelle au dessin).
    private val sparkle = Path().apply {
        moveTo(0f, -1f)
        quadTo(0.12f, -0.12f, 1f, 0f)
        quadTo(0.12f, 0.12f, 0f, 1f)
        quadTo(-0.12f, 0.12f, -1f, 0f)
        quadTo(-0.12f, -0.12f, 0f, -1f)
        close()
    }
    private val clip = Path()

    /** Passe à [newMode] ; [animate] faux (barre pas encore affichée) ou animations coupées : pose directe, sans ressort. */
    fun setMode(newMode: SendIconMode, animate: Boolean) {
        if (mode == newMode) return
        val previous = mode
        mode = newMode
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            cancel()
            launching = false
            ringTime = -1f
            enterDelay = 0f
            orbit.snap(orbitTarget())
            arrowSpring.snap(arrowTarget())
            stopSpring.snap(stopTarget())
            invalidateSelf()
            return
        }
        // L'icône qui arrive attend que l'orbite se soit repliée (ou que l'icône précédente soit partie).
        if (newMode != SendIconMode.LOADING) {
            enterDelay = if (previous == SendIconMode.LOADING) ORBIT_TO_ICON_DELAY_S else ICON_SWAP_DELAY_S
        }
        ensureTicking()
    }

    /** Décollage de la flèche à l'appui sur envoyer. Sans effet hors du mode [SendIconMode.SEND] ou sans animations. */
    fun launch() {
        if (mode != SendIconMode.SEND || !ValueAnimator.areAnimatorsEnabled()) return
        launching = true
        launchTime = 0f
        ringTime = 0f
        ensureTicking()
    }

    /** Arrête l'animation en cours (vue détachée) en gardant la pose actuelle. */
    fun cancel() {
        ticker?.cancel()
        ticker = null
        lastFrameNanos = 0L
    }

    private fun orbitTarget() = if (mode == SendIconMode.LOADING) 1f else 0f

    private fun arrowTarget() = if (mode == SendIconMode.SEND && !launching && enterDelay <= 0f) 1f else 0f

    private fun stopTarget() = if (mode == SendIconMode.STOP && !launching && enterDelay <= 0f) 1f else 0f

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
        if (enterDelay > 0f && mode != SendIconMode.LOADING) enterDelay = (enterDelay - dt).coerceAtLeast(0f)
        if (ringTime >= 0f) {
            ringTime += dt
            if (ringTime > SendIconPose.RING_DELAY_S + SendIconPose.RING_S) ringTime = -1f
        }
        if (launching) {
            launchTime += dt
            if (launchTime >= SendIconPose.LAUNCH_S) {
                launching = false
                // La flèche repart de zéro : si on est encore en mode envoi, elle remonte d'en bas avec un ressort.
                arrowSpring.snap(0f)
            }
        }
        orbit.step(orbitTarget(), dt / ORBIT_FOLD_S)
        arrowSpring.step(arrowTarget(), dt)
        stopSpring.step(stopTarget(), dt)
        invalidateSelf()
        val calm = mode != SendIconMode.LOADING && !launching && ringTime < 0f && enterDelay <= 0f &&
            orbit.value <= 0f && arrowSpring.isSettled(arrowTarget()) && stopSpring.isSettled(stopTarget())
        if (calm) cancel()
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
        ring.color = color

        val save = canvas.save()
        canvas.translate(b.exactCenterX(), b.exactCenterY())
        val unit = b.width() / GRID
        canvas.scale(unit, unit)
        // Coupe au cercle de la face du bouton : la flèche et l'onde en sortent sans déborder sur l'ombre ni le voisinage.
        clip.rewind()
        clip.addCircle(0f, 0f, faceRadiusPx / unit, Path.Direction.CW)
        canvas.clipPath(clip)

        drawOrbit(canvas, a)
        drawArrow(canvas)
        drawStop(canvas)
        drawRing(canvas, a)
        canvas.restoreToCount(save)
    }

    private fun drawOrbit(canvas: Canvas, alpha: Int) {
        val scale = SendIconPose.orbitScale(orbit.value)
        if (scale <= 0f) return
        val turns = time / SendIconPose.ORBIT_PERIOD_S
        for (i in SendIconPose.SPARKLE_SCALES.indices) {
            val (x, y) = SendIconPose.sparklePosition(i, if (ValueAnimator.areAnimatorsEnabled()) turns else 0f)
            val half = SendIconPose.SPARKLE_SIZE / 2f * SendIconPose.SPARKLE_SCALES[i] * scale
            fill.alpha = (alpha * SendIconPose.SPARKLE_ALPHAS[i]).toInt()
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(turns * FULL_TURN)
            canvas.scale(half, half)
            canvas.drawPath(sparkle, fill)
            canvas.restore()
        }
        fill.alpha = alpha
    }

    private fun drawArrow(canvas: Canvas) {
        if (launching) {
            val pose = SendIconPose.launchPose(launchTime)
            if (!pose.visible) return
            canvas.save()
            canvas.translate(0f, pose.offsetY)
            canvas.scale(1f, pose.scaleY, 0f, 0f)
            val before = stroke.alpha
            stroke.alpha = (before * pose.alpha).toInt()
            drawCentered(canvas, arrow, stroke)
            stroke.alpha = before
            canvas.restore()
            return
        }
        val v = arrowSpring.value
        if (v <= 0f) return
        canvas.save()
        canvas.translate(0f, (1f - v) * RISE)
        canvas.scale(v, v)
        drawCentered(canvas, arrow, stroke)
        canvas.restore()
    }

    private fun drawStop(canvas: Canvas) {
        val v = stopSpring.value
        if (v <= 0f) return
        canvas.save()
        canvas.translate(0f, (1f - v) * RISE)
        canvas.scale(v, v)
        canvas.translate(-CENTER, -CENTER)
        canvas.drawRoundRect(stopSquare, STOP_RADIUS, STOP_RADIUS, fill)
        canvas.restore()
    }

    private fun drawRing(canvas: Canvas, alpha: Int) {
        if (ringTime < 0f) return
        val progress = SendIconPose.ringProgress(ringTime) ?: return
        ring.alpha = (alpha * RING_ALPHA * (1f - progress)).toInt()
        val radius = faceRadiusPx / (bounds.width() / GRID) * SendIconPose.ringRadiusShare(progress)
        canvas.drawCircle(0f, 0f, radius, ring)
    }

    private fun drawCentered(canvas: Canvas, path: Path, paint: Paint) {
        canvas.save()
        canvas.translate(-CENTER, -CENTER)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    /**
     * Bouton masqué : l'animation s'arrête et l'icône prend aussitôt sa pose d'arrivée. Sans cela un ressort interrompu en
     * plein vol (clavier fermé, barre masquée) laisse l'icône figée entre deux poses au retour, car rien ne la relance.
     */
    private fun settle() {
        launching = false
        ringTime = -1f
        enterDelay = 0f
        orbit.snap(orbitTarget())
        arrowSpring.snap(arrowTarget())
        stopSpring.snap(stopTarget())
        invalidateSelf()
    }

    /** L'animation ne tourne que tant que le bouton est visible : elle s'arrête quand la barre est masquée ou détachée. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            cancel()
            settle()
        } else if (ValueAnimator.areAnimatorsEnabled() && (mode == SendIconMode.LOADING || launching || ringTime >= 0f)) {
            ensureTicking()
        }
        return changed
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        ring.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun setTintMode(tintMode: PorterDuff.Mode?) = Unit

    /** Valeur qui se rapproche linéairement de sa cible (l'orbite qui se replie ou se déploie). */
    private class ProgressValue(var value: Float) {
        fun step(target: Float, delta: Float) {
            value = if (target > value) minOf(target, value + delta) else maxOf(target, value - delta)
        }

        fun snap(target: Float) {
            value = target
        }
    }

    private companion object {
        const val GRID = 24f
        const val CENTER = 12f
        const val RISE = 8f
        const val STOP_RADIUS = 2f
        const val RING_STROKE = 1.7f
        const val RING_ALPHA = 0.55f
        const val FULL_TURN = 360f
        const val ORBIT_FOLD_S = 0.3f
        const val ORBIT_TO_ICON_DELAY_S = 0.2f
        const val ICON_SWAP_DELAY_S = 0.1f
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
    }
}
