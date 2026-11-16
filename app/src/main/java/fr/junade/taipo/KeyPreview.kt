package fr.junade.taipo

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

/**
 * Story 1.10 : bulle d'agrandissement affichée au-dessus de la touche de caractère pressée, extraite de [KeyboardView]
 * (audit 2.1, étape 8). La vue décide quelle touche est agrandie et fournit sa position, son libellé et les mesures dont la bulle
 * dépend ([Geometry]).
 *
 * Seules les touches de caractère (lettres, chiffres, symboles) ont une bulle : les touches de fonction (espace, maj,
 * retour arrière, entrée, bascule) ont déjà leur propre retour visuel (fond éclairci). Elle disparaît aussitôt ([dismiss]) dès
 * qu'une bulle d'accents (1.8) s'affiche par-dessus, ou à l'annulation.
 *
 * Animée depuis le 16/11/2026 (forme donnée par le style d'animation, [MotionStyle.bubble] ; pose calculée par
 * [KeyPreviewPose]) : la bulle entre au lieu de surgir, et sort au relâchement ([release]), jamais moins de
 * [KeyPreviewPose.MIN_SHOW_MS] après l'appui. Les bulles sont indépendantes : en frappe rapide, plusieurs s'affichent en même
 * temps (sauf Glisse, où la même bulle passe d'une touche à la suivante).
 */
internal class KeyPreview(private val view: View) {

    /** Mesures et libellés fournis par la vue qui dessine la bulle. */
    interface Geometry {
        /** Emplacement de la touche [key] dans la vue (le RectF peut être réutilisé : il est copié aussitôt), ou null. */
        fun keyBounds(key: Key): RectF?

        /** Texte à afficher dans la bulle de [key] (majuscule comprise), ou null pour ne rien dessiner. */
        fun text(key: Key): String?
        val headroom: Float
        val content: KeyboardWidth
        val maxWidth: Float
        val cornerRadius: Float
        val gap: Float
        val textSize: Float

        /** Retrait de la face de la touche dans son emplacement (Goutte : la bulle part de la face). */
        val keyInset: Float
    }

    /** Style imposé (aperçus de la page « Apparence ») ; null : celui du clavier ([MotionStyle.current]). */
    var styleOverride: MotionStyle? = null

    private val style: MotionStyle get() = styleOverride ?: MotionStyle.current
    private val animation: KeyPreviewAnimation get() = style.bubble

    private class Bubble(var key: Key, val downAt: Long) {
        var exitAt = NOT_EXITING

        /** Dernier appui montré par cette bulle (Glisse : celui de la touche qui l'a reprise) : point de départ de la durée minimale. */
        var shownAt = downAt
        var fresh = true

        /**
         * Lettre affichée, lue au premier dessin puis gardée : après le relâchement, la majuscule automatique peut se couper
         * pendant le maintien, la bulle ne doit pas passer de « B » à « b ».
         */
        var text: String? = null
        var previousText: String? = null

        /** Glisse : départ du glissement depuis la dernière position dessinée, et fondu croisé avec [previousText]. */
        var swapAt = 0L
        var moveAt = Long.MIN_VALUE / 2
        var fromLeft = 0f
        var fromTop = 0f
        var lastLeft = Float.NaN
        var lastTop = Float.NaN
    }

    private val bubbles = ArrayList<Bubble>(MAX_BUBBLES)
    private var active: Bubble? = null

    /** Touche dont la bulle est affichée et encore appuyée, ou null. */
    val key: Key? get() = active?.key

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = view.context.themeColor(R.color.key_popup) }
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = view.context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(view.context)
    }
    private val popupAlpha = previewPaint.alpha
    private val textAlpha = previewTextPaint.alpha
    private val keyRect = RectF()
    private val previewRect = RectF()
    private val faceRect = RectF()
    private val dropPath = Path()

    /**
     * Appui sur [key] à l'instant [now] (ms, `SystemClock.uptimeMillis`). Null : appui sur une touche sans bulle, la bulle
     * affichée est relâchée. [sliding] : le doigt a glissé d'une touche à l'autre, l'ancienne bulle disparaît aussitôt (sauf
     * Glisse, qui la déplace).
     */
    fun press(key: Key?, now: Long, sliding: Boolean = false) {
        if (key == null) {
            release(now)
            return
        }
        if (active?.key == key) return
        if (animation == KeyPreviewAnimation.GLIDE) {
            val last = bubbles.lastOrNull()
            if (last != null && !last.lastLeft.isNaN()) {
                last.previousText = last.text
                last.text = null
                last.key = key
                last.swapAt = now
                last.moveAt = now
                last.fromLeft = last.lastLeft
                last.fromTop = last.lastTop
                last.exitAt = NOT_EXITING
                last.shownAt = now
                last.fresh = false
                bubbles.retainAll { it === last }
                active = last
                return
            }
        }
        if (sliding) bubbles.clear()
        // Les bulles précédentes poursuivent leur affichage minimal et leur sortie, en parallèle.
        if (bubbles.size >= MAX_BUBBLES) bubbles.removeAt(0)
        Bubble(key, now).also {
            bubbles += it
            active = it
        }
    }

    /** Relâchement de la touche affichée : la bulle sort, une fois affichée au moins [KeyPreviewPose.MIN_SHOW_MS] depuis l'appui. */
    fun release(now: Long) {
        active?.let { it.exitAt = maxOf(now, it.shownAt + KeyPreviewPose.MIN_SHOW_MS) }
        active = null
    }

    /** Fait disparaître toute bulle sans animation (bulle d'accents, rotation, glissement, annulation). */
    fun dismiss() {
        bubbles.clear()
        active = null
    }

    /** Vrai tant qu'une bulle bouge (entrée, glissement, maintien ou sortie) : la vue doit demander l'image suivante. */
    fun isRunning(now: Long): Boolean {
        val enter = KeyPreviewPose.enterDurationMs(style)
        return bubbles.any { it.exitAt != NOT_EXITING || now - it.downAt < enter || now - it.moveAt < GLIDE_MOVE_MS }
    }

    /** Dessine les bulles à l'instant [now] ; celles dont la sortie est terminée sont oubliées. */
    fun draw(canvas: Canvas, now: Long, g: Geometry) {
        if (bubbles.isEmpty()) return
        val animated = ValueAnimator.areAnimatorsEnabled()
        val current = style
        val iterator = bubbles.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            val sinceExit = if (b.exitAt == NOT_EXITING) -1L else now - b.exitAt
            val pose = KeyPreviewPose.pose(current.bubble, current, now - b.downAt, sinceExit, animated, b.fresh)
            if (pose == null) {
                iterator.remove()
                if (active === b) active = null
            } else {
                drawOne(canvas, b, pose, now, animated, g)
            }
        }
    }

    private fun drawOne(canvas: Canvas, b: Bubble, pose: KeyPreviewPose.Pose, now: Long, animated: Boolean, g: Geometry) {
        val bounds = g.keyBounds(b.key) ?: return
        keyRect.set(bounds)
        val text = b.text ?: g.text(b.key)?.also { b.text = it } ?: return
        placeBubble(g)
        if (animation == KeyPreviewAnimation.GLIDE) glideTo(b, now, animated)
        if (animation == KeyPreviewAnimation.DROP) drawDrop(canvas, pose, text, g) else drawBubble(canvas, b, pose, text, now, g)
    }

    /** Pose dans [previewRect] la bulle de la touche [keyRect], à sa place finale (calcul d'origine de la story 1.10). */
    private fun placeBubble(g: Geometry) {
        val previewWidth = (keyRect.width() * 1.6f).coerceAtMost(g.maxWidth) * PREVIEW_SCALE
        val previewHeight = keyRect.height() * 1.8f * PREVIEW_SCALE
        // Place disponible au-dessus de la vue : comme la bulle d'accents (1.8), la bulle peut
        // recouvrir la barre d'actions qui la surmonte, pas au-delà de la fenêtre.
        val left = (keyRect.centerX() - previewWidth / 2f)
            .coerceIn(g.content.leftPx, (g.content.rightPx - previewWidth).coerceAtLeast(g.content.leftPx))
        val previewBottom = keyRect.top - g.gap
        val previewTop = (previewBottom - previewHeight).coerceAtLeast(-g.headroom)
        previewRect.set(left, previewTop, left + previewWidth, previewBottom)
    }

    /** Glisse : la bulle va de sa dernière position à celle de la nouvelle touche. */
    private fun glideTo(b: Bubble, now: Long, animated: Boolean) {
        val m = if (animated) KeyPreviewPose.easeOut((now - b.moveAt).toFloat() / GLIDE_MOVE_MS) else 1f
        if (m < 1f) {
            val left = b.fromLeft + (previewRect.left - b.fromLeft) * m
            val top = b.fromTop + (previewRect.top - b.fromTop) * m
            previewRect.offsetTo(left, top)
        }
        b.lastLeft = previewRect.left
        b.lastTop = previewRect.top
    }

    private fun drawBubble(canvas: Canvas, b: Bubble, pose: KeyPreviewPose.Pose, text: String, now: Long, g: Geometry) {
        val pivotX = previewRect.centerX()
        val pivotY = previewRect.bottom
        canvas.save()
        canvas.translate(0f, pose.offsetY * keyRect.height())
        canvas.scale(pose.scaleX, pose.scaleY, pivotX, pivotY)
        previewPaint.alpha = (popupAlpha * pose.alpha).roundToInt()
        canvas.drawRoundRect(previewRect, g.cornerRadius, g.cornerRadius, previewPaint)
        previewPaint.alpha = popupAlpha
        setTextSize(g.textSize * PREVIEW_SCALE)
        val baseline = previewRect.centerY() - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f +
            pose.letterOffsetY * keyRect.height()
        val previous = b.previousText
        val swap = if (previous == null) 1f else ((now - b.swapAt).toFloat() / GLIDE_SWAP_MS).coerceIn(0f, 1f)
        drawText(canvas, text, baseline, pose.alpha * swap)
        if (previous != null && swap < 1f) drawText(canvas, previous, baseline, pose.alpha * (1f - swap))
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, text: String, baseline: Float, alpha: Float) {
        previewTextPaint.alpha = (textAlpha * alpha).roundToInt()
        canvas.drawText(text, previewRect.centerX(), baseline, previewTextPaint)
        previewTextPaint.alpha = textAlpha
    }

    /**
     * Goutte : une seule forme, de la face de la touche ([faceRect]) à la bulle, reliée par un col qui se pince. [grow] 0 : la
     * forme est la touche ; 1 : la bulle est à sa place.
     */
    private fun drawDrop(canvas: Canvas, pose: KeyPreviewPose.Pose, text: String, g: Geometry) {
        val k = pose.grow
        faceRect.set(keyRect)
        faceRect.inset(g.keyInset, g.keyInset)
        val top = lerp(faceRect.top, previewRect.top, k)
        val left = lerp(faceRect.left, previewRect.left, k)
        val right = lerp(faceRect.right, previewRect.right, k)
        val bottom = lerp(faceRect.top + faceRect.height() * DROP_START_HEIGHT, previewRect.bottom, k.coerceAtMost(1f))
        val r = g.cornerRadius
        val mid = (bottom + faceRect.top) / 2f
        val pinch = k.coerceIn(0f, 1f) * faceRect.width() * DROP_PINCH
        dropPath.rewind()
        dropPath.moveTo(left + r, top)
        dropPath.lineTo(right - r, top)
        dropPath.quadTo(right, top, right, top + r)
        dropPath.lineTo(right, bottom - r)
        dropPath.cubicTo(right, bottom, faceRect.right - pinch, mid, faceRect.right, faceRect.top + r)
        dropPath.lineTo(faceRect.right, faceRect.bottom - r)
        dropPath.quadTo(faceRect.right, faceRect.bottom, faceRect.right - r, faceRect.bottom)
        dropPath.lineTo(faceRect.left + r, faceRect.bottom)
        dropPath.quadTo(faceRect.left, faceRect.bottom, faceRect.left, faceRect.bottom - r)
        dropPath.lineTo(faceRect.left, faceRect.top + r)
        dropPath.cubicTo(faceRect.left + pinch, mid, left, bottom, left, bottom - r)
        dropPath.lineTo(left, top + r)
        dropPath.quadTo(left, top, left + r, top)
        dropPath.close()
        canvas.drawPath(dropPath, previewPaint)
        setTextSize(lerp(g.textSize * PREVIEW_SCALE * DROP_START_TEXT, g.textSize * PREVIEW_SCALE, k.coerceIn(0f, 1f)))
        val baseline = (top + bottom) / 2f - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f
        canvas.drawText(text, (left + right) / 2f, baseline, previewTextPaint)
    }

    private fun setTextSize(size: Float) {
        if (previewTextPaint.textSize != size) previewTextPaint.textSize = size
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        // Taille de la bulle d'agrandissement (largeur, hauteur et texte) : 60 % de la taille d'origine (0,5 + 20 %).
        const val PREVIEW_SCALE = 0.6f
        const val NOT_EXITING = Long.MAX_VALUE
        const val MAX_BUBBLES = 6
        const val GLIDE_MOVE_MS = 80L
        const val GLIDE_SWAP_MS = 70f
        const val DROP_START_HEIGHT = 0.9f
        const val DROP_PINCH = 0.14f
        const val DROP_START_TEXT = 0.8f
    }
}
