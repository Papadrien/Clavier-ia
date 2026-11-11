package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.roundToInt

/**
 * Aperçu d'un style d'animation ([MotionStyle]) sur sa carte de la page « Apparence » : trois touches du clavier ; celle du milieu
 * est appuyée et sa bulle s'ouvre avec le ressort du style ([SpringValue] et l'étirement de Gelée, comme [KeyPopup]), reste
 * ouverte puis se referme, en boucle ([CYCLE_S]). Couleurs du clavier ([KeyboardTheme]). Animations coupées dans les réglages
 * système : la bulle est dessinée ouverte, immobile. Décoratif : la carte porte le nom du style pour TalkBack.
 */
internal class MotionPreviewView(context: Context, private val style: MotionStyle) : View(context) {

    private val spring = SpringValue(PopupOpenPose.START_SCALE, style)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var cycleSeconds = 0f
    private var lastFrameNanos = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        labelPaint.typeface = TaipoType.typeface(context, TaipoType.Weight.MEDIUM)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val corner = CORNER_DP * density

        paint.color = context.themeColor(R.color.keyboard_background)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, corner, corner, paint)

        val open = advance()
        // Trois touches en bas : la largeur se partage avec des marges égales.
        val gap = GAP_DP * density
        val keyWidth = (w - gap * (KEYS + 1)) / KEYS
        val keyHeight = minOf(KEY_HEIGHT_DP * density, h * KEY_HEIGHT_MAX_RATIO)
        val shadow = SHADOW_DP * density
        val keyTop = h - gap - keyHeight - shadow
        for (i in 0 until KEYS) {
            val left = gap + i * (keyWidth + gap)
            val pressed = i == KEYS / 2 && open != null
            paint.color = context.themeColor(R.color.key_functional_shadow)
            rect.set(left, keyTop + shadow, left + keyWidth, keyTop + keyHeight + shadow)
            canvas.drawRoundRect(rect, corner, corner, paint)
            paint.color = context.themeColor(if (pressed) R.color.key_normal else R.color.key_functional)
            val sink = if (pressed) shadow else 0f
            rect.set(left, keyTop + sink, left + keyWidth, keyTop + keyHeight + sink)
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
        val middleLeft = gap + (KEYS / 2) * (keyWidth + gap)
        labelPaint.color = context.themeColor(R.color.key_label)
        labelPaint.textSize = keyHeight * LABEL_RATIO
        drawLabel(canvas, middleLeft + keyWidth / 2f, keyTop + keyHeight / 2f)

        if (open == null) return
        // Bulle au-dessus de la touche du milieu, pivot au haut de la touche (comme la vraie bulle).
        val bubbleWidth = keyWidth * BUBBLE_WIDTH_RATIO
        val bubbleHeight = minOf(keyHeight * BUBBLE_HEIGHT_RATIO, keyTop - gap)
        val cx = middleLeft + keyWidth / 2f
        val pivotY = keyTop
        val scaleX = open * style.stretchX(open)
        val scaleY = open * style.stretchY(open)
        canvas.save()
        canvas.scale(scaleX, scaleY, cx, pivotY)
        val alpha = (255 * PopupOpenPose.alpha(open)).roundToInt()
        paint.color = context.themeColor(R.color.key_popup)
        paint.alpha = alpha
        rect.set(cx - bubbleWidth / 2f, pivotY - bubbleHeight, cx + bubbleWidth / 2f, pivotY)
        canvas.drawRoundRect(rect, corner * BUBBLE_CORNER_RATIO, corner * BUBBLE_CORNER_RATIO, paint)
        paint.alpha = 255
        labelPaint.textSize = bubbleHeight * BUBBLE_LABEL_RATIO
        labelPaint.alpha = alpha
        drawLabel(canvas, cx, pivotY - bubbleHeight / 2f)
        labelPaint.alpha = 255
        canvas.restore()
    }

    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float) {
        val baseline = cy - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(LABEL, cx, baseline, labelPaint)
    }

    /**
     * Fait avancer la boucle jusqu'à maintenant : taille de la bulle (1 = ouverte), ou null pendant la pause où elle est fermée.
     * Demande l'image suivante tant que la vue est affichée.
     */
    private fun advance(): Float? {
        if (!ValueAnimator.areAnimatorsEnabled()) return 1f
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastFrameNanos = now
        cycleSeconds += dt
        if (cycleSeconds >= CYCLE_S) {
            cycleSeconds = 0f
            spring.snap(PopupOpenPose.START_SCALE)
        }
        if (isShown) postInvalidateOnAnimation()
        if (cycleSeconds > OPEN_S) return null
        spring.step(1f, dt * PopupOpenPose.TIME_SCALE)
        return spring.value.coerceAtLeast(0f)
    }

    private companion object {
        const val LABEL = "e"
        const val KEYS = 3
        const val CYCLE_S = 2.2f
        const val OPEN_S = 1.4f
        const val CORNER_DP = 6f
        const val GAP_DP = 6f
        const val KEY_HEIGHT_DP = 32f
        const val KEY_HEIGHT_MAX_RATIO = 0.34f
        const val SHADOW_DP = 2f
        const val LABEL_RATIO = 0.5f
        const val BUBBLE_WIDTH_RATIO = 1.3f
        const val BUBBLE_HEIGHT_RATIO = 1.7f
        const val BUBBLE_CORNER_RATIO = 1.4f
        const val BUBBLE_LABEL_RATIO = 0.55f
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
    }
}
