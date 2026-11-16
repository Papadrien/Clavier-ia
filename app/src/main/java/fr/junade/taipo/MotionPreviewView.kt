package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Aperçu d'un style d'animation ([MotionStyle]) sur sa carte de la section « Animations » de la page « Apparence » : trois
 * touches du clavier, tapées en boucle, avec la vraie bulle des touches ([KeyPreview], style imposé : son ressort et la forme de
 * sa bulle, [MotionStyle.bubble]). Une seule touche appuyée puis relâchée ; Glisse : les trois touches tapées vite, pour montrer
 * la bulle qui passe de l'une à l'autre. Couleurs du clavier ([KeyboardTheme]). Animations coupées dans les réglages système : la bulle est dessinée ouverte,
 * immobile. Décoratif : la carte porte le nom de l'animation pour TalkBack.
 */
internal class MotionPreviewView(context: Context, private val style: MotionStyle) : View(context) {

    private val preview = KeyPreview(this).apply { styleOverride = style }
    private val keys = LETTERS.map { Key(it.toString(), it.toString(), KeyAction.TypeChar(it)) }
    private val slots = List(LETTERS.length) { RectF() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private var startMs = -1L
    private var lastStep = -1
    private var corner = 0f
    private var keyHeight = 0f

    private val geometry = object : KeyPreview.Geometry {
        override fun keyBounds(key: Key): RectF? = slots.getOrNull(keys.indexOf(key))
        override fun text(key: Key): String = key.label
        override val headroom: Float get() = 0f
        override val content: KeyboardWidth get() = KeyboardWidth(0f, width.toFloat())
        override val maxWidth: Float get() = Float.MAX_VALUE
        override val cornerRadius: Float get() = corner * BUBBLE_CORNER_RATIO

        // KeyPreview dessine la lettre à 60 % de cette taille : elle remplit ainsi la moitié de la bulle.
        override val textSize: Float get() = keyHeight
        override val gap: Float get() = corner
        override val keyInset: Float get() = 0f
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        labelPaint.typeface = TaipoType.typeface(context, TaipoType.Weight.MEDIUM)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMs = -1L
        lastStep = -1
        preview.dismiss()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        corner = CORNER_DP * density
        paint.color = context.themeColor(R.color.keyboard_background)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, corner, corner, paint)

        val gap = GAP_DP * density
        val keyWidth = (w - gap * (LETTERS.length + 1)) / LETTERS.length
        keyHeight = minOf(KEY_HEIGHT_DP * density, h * KEY_HEIGHT_MAX_RATIO)
        val shadow = SHADOW_DP * density
        val keyTop = h - gap - keyHeight - shadow
        val now = System.nanoTime() / NANOS_PER_MS
        runScript(now)
        labelPaint.color = context.themeColor(R.color.key_label)
        labelPaint.textSize = keyHeight * LABEL_RATIO
        keys.forEachIndexed { i, key ->
            val left = gap + i * (keyWidth + gap)
            val pressed = preview.key == key
            slots[i].set(left, keyTop, left + keyWidth, keyTop + keyHeight)
            paint.color = context.themeColor(R.color.key_functional_shadow)
            rect.set(left, keyTop + shadow, left + keyWidth, keyTop + keyHeight + shadow)
            canvas.drawRoundRect(rect, corner, corner, paint)
            paint.color = context.themeColor(if (pressed) R.color.key_normal else R.color.key_functional)
            val sink = if (pressed) shadow else 0f
            rect.set(left, keyTop + sink, left + keyWidth, keyTop + keyHeight + sink)
            canvas.drawRoundRect(rect, corner, corner, paint)
            val baseline = rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(key.label, rect.centerX(), baseline, labelPaint)
        }
        preview.draw(canvas, now, geometry)
        if (isShown && ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
    }

    /** Joue la boucle : appuis et relâchements aux instants de [script], recommencée toutes les [CYCLE_MS]. */
    private fun runScript(now: Long) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Animations coupées : la bulle du milieu reste ouverte.
            if (preview.key == null) preview.press(keys[1], now)
            return
        }
        if (startMs < 0) startMs = now
        if (now - startMs >= CYCLE_MS) {
            startMs = now
            lastStep = -1
        }
        val elapsed = now - startMs
        val steps = if (style.bubble == KeyPreviewAnimation.GLIDE) GLIDE_SCRIPT else TAP_SCRIPT
        while (lastStep + 1 < steps.size && steps[lastStep + 1].first <= elapsed) {
            lastStep++
            val (_, index) = steps[lastStep]
            if (index >= 0) preview.press(keys[index], now) else preview.release(now)
        }
    }

    private companion object {
        const val LETTERS = "oui"
        const val CYCLE_MS = 1700L

        /** (instant en ms, touche appuyée ; -1 : relâchement). */
        val TAP_SCRIPT = listOf(150L to 1, 450L to -1)
        val GLIDE_SCRIPT = listOf(150L to 0, 260L to -1, 330L to 1, 440L to -1, 510L to 2, 620L to -1)
        const val CORNER_DP = 6f
        const val GAP_DP = 6f
        const val KEY_HEIGHT_DP = 32f
        const val KEY_HEIGHT_MAX_RATIO = 0.34f
        const val SHADOW_DP = 2f
        const val LABEL_RATIO = 0.5f
        const val BUBBLE_CORNER_RATIO = 1.4f
        const val NANOS_PER_MS = 1_000_000L
    }
}
