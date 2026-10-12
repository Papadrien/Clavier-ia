package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.ImageButton
import androidx.annotation.ColorRes

/**
 * Bouton de la barre du haut qui a deux apparences (menu « robot » ouvert) :
 * - **icône seule** (par défaut) : bouton rond de la charte (lot 08), largeur fixée par la barre ;
 * - **pilule** ([labeled]) : fond [PillKeyDrawable] (capsule), icône puis [label] dessiné au Canvas, largeur = contenu.
 *
 * L'icône est centrée dans la partie gauche par le padding droit (qui réserve la place du texte) ; le texte est dessiné
 * à droite de l'icône, sur la face du bouton (au-dessus de l'épaisseur d'ombre). Les fonds sont créés au premier besoin
 * puis réutilisés (un Drawable n'appartient qu'à une seule vue, lot 21). Le texte ne change pas le comportement du bouton :
 * description d'accessibilité, état, opacité et écouteurs restent ceux d'un [ImageButton].
 */
@SuppressLint("ViewConstructor")
internal class LabeledIconButton(context: Context, private val style: BarStyle) : ImageButton(context) {

    private val shadowHeight = context.dimen(R.dimen.taipo_key_shadow_height).toInt()
    private val paddingH = context.dimen(R.dimen.taipo_bar_pill_padding_horizontal).toInt()
    private val iconGap = context.dimen(R.dimen.taipo_bar_pill_icon_gap).toInt()
    private val iconSize = context.dimen(R.dimen.taipo_bar_pill_icon_size).toInt()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.text_primary)
        useTaipoFont(context, TaipoType.Weight.BOLD)
        textSize = context.fixedHeightTextDimen(R.dimen.taipo_bar_pill_text_size)
    }
    private val roundBackgrounds = HashMap<Int, android.graphics.drawable.Drawable>()
    private val pillBackgrounds = HashMap<Int, android.graphics.drawable.Drawable>()
    private var textWidth = 0

    /** Texte de la pilule (inutilisé tant que le bouton est en icône seule). */
    var label: String = ""
        set(value) {
            if (field == value) return
            field = value
            textWidth = Math.ceil(textPaint.measureText(value).toDouble()).toInt()
            if (labeled) {
                applyPadding()
                requestLayout()
                invalidate()
            }
        }

    /** Couleur de la face du bouton (violet d'alerte pendant l'enregistrement, gris sinon). */
    @get:ColorRes
    var face: Int = R.color.surface_button
        set(value) {
            if (field == value) return
            field = value
            applyBackground()
        }

    /** Vrai : pilule icône + texte ; faux : bouton rond à icône seule. */
    var labeled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            applyPadding()
            applyBackground()
            requestLayout()
            invalidate()
        }

    /** Largeur de la pilule : marges, icône, espace et texte. */
    fun labeledWidth(): Int = 2 * paddingH + iconSize + iconGap + textWidth

    private fun applyPadding() {
        if (labeled) {
            setPadding(paddingH, 0, paddingH + iconGap + textWidth, shadowHeight)
        } else {
            setPadding(0, 0, 0, shadowHeight)
        }
    }

    private fun applyBackground() {
        val faceRes = face
        background = if (labeled) {
            pillBackgrounds.getOrPut(faceRes) { style.pillBackground(faceRes) }
        } else {
            roundBackgrounds.getOrPut(faceRes) { style.roundBackground(faceRes) }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (labeled) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(labeledWidth(), MeasureSpec.EXACTLY), heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!labeled) return
        val faceHeight = (height - shadowHeight).toFloat()
        val baseline = faceHeight / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, (paddingH + iconSize + iconGap).toFloat(), baseline, textPaint)
    }
}
