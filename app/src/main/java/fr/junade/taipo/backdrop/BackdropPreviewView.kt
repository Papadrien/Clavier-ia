package fr.junade.taipo.backdrop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import fr.junade.taipo.R

/**
 * Vignette d'un fond du clavier (page « Thème ») : le motif dans la couleur et le thème choisis, et trois rangées de petites
 * touches par-dessus (translucides sur un motif, pleines sur le fond uni). Le motif avance avec le défilement de la page
 * ([advance]), pour montrer qu'il est animé.
 */
class BackdropPreviewView(context: Context) : View(context) {

    var backdrop: KeyboardBackdrop = KeyboardBackdrop.PLAIN
        set(value) {
            if (field == value) return
            field = value
            painter = BackdropPainter.of(value)
            invalidate()
        }

    var seed: Long = 1L
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var painter: BackdropPainter? = null
    private var colors: BackdropColors? = null
    private var keyColor = 0
    private val motion = BackdropMotion()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyRect = RectF()
    private val keyRadius = resources.getDimension(R.dimen.taipo_backdrop_preview_key_radius)

    /** Couleurs de l'aperçu : fond du clavier, couleur d'accent, thème sombre ou clair, couleur des touches. */
    fun setColors(base: Int, accent: Int, dark: Boolean, key: Int) {
        colors = BackdropColors(base, accent, dark)
        keyColor = key
        invalidate()
    }

    /** Défilement de la page : le motif avance d'autant ([amount] en pixels défilés), puis s'arrête avec lui. */
    fun advance(amount: Float) {
        if (painter == null) return
        motion.setPhase(motion.phase + amount * SCROLL_TO_PHASE)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val currentColors = colors ?: return
        val currentPainter = painter
        if (currentPainter == null) canvas.drawColor(currentColors.base) else currentPainter.drawFrame(canvas, width, height, seed, currentColors, motion)
        keyPaint.color = keyColor
        keyPaint.alpha = if (currentPainter == null) OPAQUE else TRANSLUCENT
        val gap = width * GAP_RATIO
        val rows = 3
        val top = height * 0.35f
        val rowHeight = (height - top - gap) / rows - gap
        for (row in 0 until rows) {
            val keys = KEYS_PER_ROW - row
            val inset = row * (width / KEYS_PER_ROW) / 2f
            val keyWidth = (width - 2 * inset - gap * (keys + 1)) / keys
            for (k in 0 until keys) {
                val left = inset + gap + k * (keyWidth + gap)
                val y = top + gap + row * (rowHeight + gap)
                keyRect.set(left, y, left + keyWidth, y + rowHeight)
                canvas.drawRoundRect(keyRect, keyRadius, keyRadius, keyPaint)
            }
        }
    }

    private companion object {
        /** Avance du motif par pixel défilé. Demande du 12/11/2026 : divisée par 4 (0,012 auparavant). */
        const val SCROLL_TO_PHASE = 0.003f
        const val KEYS_PER_ROW = 7
        const val GAP_RATIO = 0.025f
        const val OPAQUE = 255

        /** Même transparence que les touches du vrai clavier sur un fond à motif. */
        const val TRANSLUCENT = fr.junade.taipo.KeyRenderer.TRANSLUCENT_FACE_ALPHA
    }
}
