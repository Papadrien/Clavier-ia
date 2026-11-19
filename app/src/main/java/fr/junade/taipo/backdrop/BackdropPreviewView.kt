package fr.junade.taipo.backdrop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import fr.junade.taipo.R

/**
 * Vignette d'un fond du clavier (page « Thème ») : le motif dans la couleur et le thème choisis, et trois rangées de petites
 * touches par-dessus (translucides sur un motif, pleines sur le fond uni). Le motif avance avec le défilement de la page
 * ([advance]), pour montrer qu'il est animé. Fond « Image » : l'image choisie ([photo]), ou, sans image, un pictogramme d'image
 * dans la couleur d'accent qui invite à en choisir une.
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

    /** Image du fond « Image » (null : pas encore choisie). */
    var photo: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    private var painter: BackdropPainter? = null
    private val photoDrawer = PhotoBackdropDrawer()
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPath = Path()
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
        val currentPhoto = photo?.takeIf { !it.isRecycled }
        when {
            backdrop == KeyboardBackdrop.PHOTO && currentPhoto != null ->
                photoDrawer.draw(canvas, currentPhoto, width, height, currentColors.base)
            backdrop == KeyboardBackdrop.PHOTO -> {
                canvas.drawColor(currentColors.base)
                drawImageGlyph(canvas, currentColors.accent)
            }
            currentPainter == null -> canvas.drawColor(currentColors.base)
            else -> currentPainter.drawFrame(canvas, width, height, seed, currentColors, motion)
        }
        keyPaint.color = keyColor
        keyPaint.alpha = if (backdrop.isPattern) TRANSLUCENT else OPAQUE
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

    /** Pictogramme « image » (cadre, montagnes, soleil), centré dans la zone au-dessus des touches. */
    private fun drawImageGlyph(canvas: Canvas, color: Int) {
        val size = height * GLYPH_RATIO
        val cx = width / 2f
        val cy = height * 0.35f / 2f + size * 0.1f
        val left = cx - size * 0.6f
        val top = cy - size * 0.45f
        val right = cx + size * 0.6f
        val bottom = cy + size * 0.45f
        glyphPaint.color = color
        glyphPaint.strokeWidth = size * 0.08f
        keyRect.set(left, top, right, bottom)
        canvas.drawRoundRect(keyRect, size * 0.12f, size * 0.12f, glyphPaint)
        glyphPath.rewind()
        glyphPath.moveTo(left + size * 0.1f, bottom - size * 0.12f)
        glyphPath.lineTo(cx - size * 0.15f, cy - size * 0.05f)
        glyphPath.lineTo(cx + size * 0.1f, cy + size * 0.2f)
        glyphPath.lineTo(cx + size * 0.25f, cy + size * 0.05f)
        glyphPath.lineTo(right - size * 0.1f, bottom - size * 0.12f)
        canvas.drawPath(glyphPath, glyphPaint)
        canvas.drawCircle(cx + size * 0.28f, cy - size * 0.18f, size * 0.08f, glyphPaint)
    }

    private companion object {
        /** Taille du pictogramme « image » par rapport à la hauteur de la vignette. */
        const val GLYPH_RATIO = 0.28f

        /** Avance du motif par pixel défilé. Demande du 12/11/2026 : divisée par 4 (0,012 auparavant). */
        const val SCROLL_TO_PHASE = 0.003f
        const val KEYS_PER_ROW = 7
        const val GAP_RATIO = 0.025f
        const val OPAQUE = 255

        /** Même transparence que les touches du vrai clavier sur un fond à motif. */
        const val TRANSLUCENT = fr.junade.taipo.KeyRenderer.TRANSLUCENT_FACE_ALPHA
    }
}
