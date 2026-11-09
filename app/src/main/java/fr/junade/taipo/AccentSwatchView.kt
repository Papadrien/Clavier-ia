package fr.junade.taipo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Pastille d'une couleur d'accent (page « Thème », section « Couleur ») : disque coupé en diagonale, couleur principale en haut à
 * gauche et secondaire en bas à droite, comme la touche Entrée et son épaisseur. La pastille choisie est entourée d'un anneau de la
 * couleur du texte. Couleur réservée au pass premium ([isLocked]) : un petit cadenas blanc au centre. Décorative : l'élément
 * tactile qui la contient porte le libellé lu par TalkBack. Aucune allocation au dessin.
 */
class AccentSwatchView(context: Context) : View(context) {

    var palette: AccentPalette = AccentPalette.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    var isChosen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var isLocked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val lockIcon = checkNotNull(context.getDrawable(R.drawable.ic_lock)) { "Icône cadenas introuvable" }.mutate().apply {
        setTint(context.getColor(R.color.text_on_accent))
    }
    private val lockSize = resources.getDimensionPixelSize(R.dimen.taipo_accent_swatch_lock_size)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.taipo_accent_swatch_ring)
        color = context.getColor(R.color.text_primary)
    }
    private val ringGap = resources.getDimension(R.dimen.taipo_accent_swatch_ring_gap)
    private val oval = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        // Place de l'anneau toujours réservée : la pastille ne change pas de taille quand elle est choisie.
        val radius = size / 2f - ring.strokeWidth - ringGap
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        fill.color = palette.primary
        canvas.drawArc(oval, DIAGONAL_START, HALF_TURN, true, fill)
        fill.color = palette.secondary
        canvas.drawArc(oval, DIAGONAL_START - HALF_TURN, HALF_TURN, true, fill)
        if (isChosen) canvas.drawCircle(cx, cy, size / 2f - ring.strokeWidth / 2f, ring)
        if (isLocked) {
            val half = minOf(lockSize.toFloat(), radius) / 2f
            lockIcon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            lockIcon.draw(canvas)
        }
    }

    private companion object {
        /** Angle (degrés, sens horaire depuis 3 h) où commence la moitié principale : la diagonale va du bas gauche au haut droit. */
        const val DIAGONAL_START = 135f
        const val HALF_TURN = 180f
    }
}
