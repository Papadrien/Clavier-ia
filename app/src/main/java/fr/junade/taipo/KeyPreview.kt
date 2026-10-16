package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Story 1.10 : bulle d'agrandissement affichée au-dessus de la touche de caractère pressée, extraite de [KeyboardView]
 * (audit 2.1, étape 8). Le code est celui d'avant l'extraction ; la vue décide quelle touche est agrandie et fournit sa
 * position, son libellé et les mesures dont la bulle dépend.
 *
 * Seules les touches de caractère (lettres, chiffres, symboles) ont une bulle : les touches de fonction (espace, maj,
 * retour arrière, entrée, bascule) ont déjà leur propre retour visuel (fond éclairci). Elle disparaît dès qu'une bulle
 * d'accents (1.8) s'affiche par-dessus, ou au relâchement/annulation.
 */
internal class KeyPreview(private val view: View) {

    /** Touche agrandie, ou null. */
    var key: Key? = null

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = view.context.themeColor(R.color.key_popup) }
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = view.context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(view.context)
    }
    private val previewRect = RectF()
    private val charBuffer = CharArray(1)

    /**
     * Dessine la bulle au-dessus de la touche de position [bounds]. [upper] : majuscule à afficher, ou null pour afficher
     * [label]. [headroom] : place disponible au-dessus de la vue ; [content] : zone horizontale des touches ; les autres
     * paramètres sont les dimensions courantes de la vue.
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        upper: Char?,
        label: String,
        headroom: Float,
        content: KeyboardWidth,
        maxWidth: Float,
        cornerRadius: Float,
        gap: Float,
        textSize: Float,
    ) {
        val previewWidth = (bounds.width() * 1.6f).coerceAtMost(maxWidth) * PREVIEW_SCALE
        val previewHeight = bounds.height() * 1.8f * PREVIEW_SCALE
        // Place disponible au-dessus de la vue : comme la bulle d'accents (1.8), la bulle peut
        // recouvrir la barre d'actions qui la surmonte, pas au-delà de la fenêtre.
        val left = (bounds.centerX() - previewWidth / 2f)
            .coerceIn(content.leftPx, (content.rightPx - previewWidth).coerceAtLeast(content.leftPx))
        val previewBottom = bounds.top - gap
        val previewTop = (previewBottom - previewHeight).coerceAtLeast(-headroom)

        previewRect.set(left, previewTop, left + previewWidth, previewBottom)
        canvas.drawRoundRect(previewRect, cornerRadius, cornerRadius, previewPaint)

        val scaledTextSize = textSize * PREVIEW_SCALE
        if (previewTextPaint.textSize != scaledTextSize) previewTextPaint.textSize = scaledTextSize
        val baseline = previewRect.centerY() - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f
        if (upper != null) {
            charBuffer[0] = upper
            canvas.drawText(charBuffer, 0, 1, previewRect.centerX(), baseline, previewTextPaint)
        } else {
            canvas.drawText(label, previewRect.centerX(), baseline, previewTextPaint)
        }
    }

    private companion object {
        // Taille de la bulle d'agrandissement (largeur, hauteur et texte) : 60 % de la taille d'origine (0,5 + 20 %).
        const val PREVIEW_SCALE = 0.6f
    }
}
