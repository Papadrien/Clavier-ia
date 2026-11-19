package fr.junade.taipo

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Choreographer
import fr.junade.taipo.backdrop.AmbientTyping
import fr.junade.taipo.backdrop.BackdropColors
import fr.junade.taipo.backdrop.BackdropMotion
import fr.junade.taipo.backdrop.BackdropPainter
import fr.junade.taipo.backdrop.KeyboardBackdrop
import fr.junade.taipo.backdrop.PhotoBackdropDrawer

/**
 * Fond unique de tout le clavier : barre du haut, touches et panneaux (emoji, Smart Clipboard) sont
 * transparents et laissent voir ce fond, posé sur la vue racine de l'IME. Les animations
 * d'arrière-plan se dessinent ici, une seule fois, et passent donc derrière la barre et le clavier
 * sans raccord visible.
 *
 * Clavier flottant : [cornerRadius] arrondit les coins de la carte, et le contour ([getOutline]) donne son ombre.
 *
 * Fond du clavier (page « Thème ») : uni, ou un motif généré ([setBackdrop]) dans la couleur d'accent. Animé ([animated]), le
 * motif ne bouge que pendant la frappe ([onKeyPress]) : une image par trame tant que le mouvement dure ([BackdropMotion]),
 * rien à l'arrêt. Animations Android coupées : le motif reste fixe.
 *
 * Voile de la barre du haut (16/11/2026) : sur un motif, la zone au-dessus des touches ([setTopBar]) est recouverte d'un voile de
 * la couleur unie du clavier, qui s'efface juste au-dessus de la première rangée. Les suggestions, en texte sans fond, restent
 * lisibles quel que soit le motif qui passe derrière (un pétale blanc derrière un mot blanc, par exemple).
 *
 * Fond « Image » (19/11/2026) : l'image de l'utilisateur ([setPhoto]) remplit le fond, recadrée au centre, fixe ; le voile de la
 * barre du haut s'y applique comme sur un motif. Tant que l'image n'est pas chargée, le fond reste uni.
 *
 * Action IA en cours ([aiBusy], 19/11/2026) : le motif s'anime comme pendant la frappe, avec des touches simulées au rythme d'une
 * frappe soutenue ([AmbientTyping]) sur la zone des touches, jusqu'à la fin de l'action. Mêmes conditions que la frappe : motif
 * animé ([animated]) et animations Android actives.
 */
class KeyboardBackgroundDrawable(backgroundColor: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val baseColor = backgroundColor
    private val rect = RectF()
    private val clip = Path()

    private val scrimPaint = Paint()
    private var barBottom = 0f
    private var scrimFade = 0f
    private var scrimShaderFor = Float.NaN

    private var painter: BackdropPainter? = null
    private var backdrop = KeyboardBackdrop.PLAIN
    private var seed = 0L
    private var colors: BackdropColors? = null
    private val motion = BackdropMotion()
    private var photo: Bitmap? = null
    private val photoDrawer = PhotoBackdropDrawer()
    private val ambientTyping = AmbientTyping()

    /** Une action IA est en cours : le motif s'anime comme si l'utilisateur tapait, tant qu'elle dure. */
    var aiBusy = false
        set(value) {
            if (field == value) return
            field = value
            if (value && canAnimate()) {
                ambientTyping.reset()
                scheduleFrame()
            }
        }

    /** Le motif s'anime pendant la frappe (réglage « Animer le fond pendant la frappe »). */
    var animated = true

    private var lastFrameNanos = 0L
    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback { now -> onFrame(now) }

    /** Rayon des coins (px) : 0 pour le clavier ancré en bas, arrondi pour le clavier flottant. */
    var cornerRadius = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    /** Motif à dessiner (ou fond uni), tirage de sa disposition et couleur d'accent. Sans effet si rien ne change. */
    fun setBackdrop(value: KeyboardBackdrop, seed: Long, accent: Int, dark: Boolean) {
        val newColors = BackdropColors(baseColor, accent, dark)
        val sameColors = colors?.let { it.accent == accent && it.dark == dark } == true
        if (value == backdrop && seed == this.seed && sameColors) return
        if (value != backdrop) painter = BackdropPainter.of(value)
        backdrop = value
        this.seed = seed
        colors = newColors
        invalidateSelf()
    }

    /** Image du fond « Image » (null : pas d'image chargée). Dessinée seulement quand le fond choisi est [KeyboardBackdrop.PHOTO]. */
    fun setPhoto(bitmap: Bitmap?) {
        if (photo === bitmap) return
        photo = bitmap
        invalidateSelf()
    }

    /**
     * Zone de la barre du haut : [bottom] px depuis le haut du fond (0 : pas de barre, pas de voile), et hauteur [fade] du dégradé
     * qui finit à [bottom]. Sans effet si rien ne change.
     */
    fun setTopBar(bottom: Float, fade: Float) {
        if (bottom == barBottom && fade == scrimFade) return
        barBottom = bottom
        scrimFade = fade
        scrimShaderFor = Float.NaN
        invalidateSelf()
    }

    /** Une touche vient d'être appuyée en ([x], [y]), coordonnées de ce fond : le motif dérive et réagit autour d'elle. */
    fun onKeyPress(x: Float, y: Float) {
        if (!canAnimate()) return
        motion.press(x - bounds.left, y - bounds.top)
        scheduleFrame()
    }

    /** Un motif animé est affiché et les animations Android sont actives. */
    private fun canAnimate(): Boolean = painter != null && animated && ValueAnimator.areAnimatorsEnabled()

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Action IA : touches simulées sur la zone des touches (sous la barre du haut), en coordonnées du fond. */
    private fun simulateTyping(dt: Float) {
        val width = bounds.width().toFloat()
        val keysTop = barBottom.coerceIn(0f, bounds.height().toFloat())
        val keysHeight = bounds.height() - keysTop
        ambientTyping.advance(dt) { fx, fy -> motion.press(fx * width, keysTop + fy * keysHeight) }
    }

    private fun onFrame(nowNanos: Long) {
        frameScheduled = false
        val dt = if (lastFrameNanos == 0L) FIRST_FRAME_S else ((nowNanos - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastFrameNanos = nowNanos
        val simulating = aiBusy && canAnimate()
        if (simulating) simulateTyping(dt)
        motion.step(dt)
        invalidateSelf()
        if ((motion.isRunning || simulating) && callback != null) {
            frameScheduled = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun draw(canvas: Canvas) {
        val currentPainter = painter
        val currentColors = colors
        val currentPhoto = photo?.takeIf { backdrop == KeyboardBackdrop.PHOTO && !it.isRecycled }
        if (currentPhoto != null) {
            drawClipped(canvas) { photoDrawer.draw(canvas, currentPhoto, bounds.width(), bounds.height(), baseColor) }
            return
        }
        if (currentPainter == null || currentColors == null) {
            if (cornerRadius <= 0f) {
                canvas.drawRect(bounds, paint)
                return
            }
            rect.set(bounds)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
            return
        }
        drawClipped(canvas) { currentPainter.drawFrame(canvas, bounds.width(), bounds.height(), seed, currentColors, motion) }
    }

    /** Fond à motif ou image : [content] dessiné dans les coins du clavier, en coordonnées du fond, puis le voile de la barre. */
    private inline fun drawClipped(canvas: Canvas, content: () -> Unit) {
        canvas.save()
        if (cornerRadius > 0f) {
            rect.set(bounds)
            clip.rewind()
            clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clip)
        } else {
            canvas.clipRect(bounds)
        }
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        content()
        drawTopBarScrim(canvas, bounds.width().toFloat())
        canvas.restore()
    }

    /** Voile au-dessus des touches : couvrant ([SCRIM_ALPHA]) jusqu'au début du dégradé, puis transparent au bas de la barre. */
    private fun drawTopBarScrim(canvas: Canvas, width: Float) {
        if (barBottom <= 0f) return
        if (scrimShaderFor != barBottom) {
            val start = (barBottom - scrimFade).coerceAtLeast(0f)
            scrimPaint.shader = LinearGradient(
                0f,
                start,
                0f,
                barBottom,
                BackdropColors.withAlpha(baseColor, SCRIM_ALPHA),
                BackdropColors.withAlpha(baseColor, 0f),
                Shader.TileMode.CLAMP,
            )
            scrimShaderFor = barBottom
        }
        canvas.drawRect(0f, 0f, width, barBottom, scrimPaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadius)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (cornerRadius > 0f) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE

    private companion object {
        /** Opacité du voile de la barre du haut : le motif s'y devine encore, sans gêner la lecture. */
        const val SCRIM_ALPHA = 0.85f
        const val FIRST_FRAME_S = 1f / 60f
        const val MAX_FRAME_S = 0.033f
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
