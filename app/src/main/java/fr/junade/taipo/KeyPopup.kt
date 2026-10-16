package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.roundToInt

/**
 * Bulle de symboles (accents, symboles, globe) affichée au-dessus d'une touche à l'appui long, extraite de [KeyboardView]
 * (audit 2.1, étape 7) : géométrie, cellule sélectionnée, relâchement et dessin. Le code est celui d'avant l'extraction ;
 * la vue garde les gestes et fournit les mesures dont la bulle dépend (touche, place disponible, zone des touches).
 */
internal class KeyPopup(private val view: View) {

    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = view.context.themeColor(R.color.key_popup) }
    private val popupSelectionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = view.context.themeColor(R.color.key_popup_selection) }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = view.context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(view.context, TaipoType.Weight.BOLD)
    }
    private val popupRect = RectF()
    private val charBuffer = CharArray(1)

    /** Touche dont la bulle est ouverte, ou null. */
    var key: Key? = null
        private set

    // Grille de la bulle ouverte : Key.popup, ou ENTER_GRID pour la touche Entrée (qui n'a pas de symboles propres).
    private var popupGrid: List<List<Char>> = emptyList()
    private var popupSelectedRow = -1
    private var popupSelectedCol = -1
    private var popupCellWidth = 0f
    private var popupCellHeight = 0f
    private var popupPadding = 0f
    private var popupPaddingV = 0f

    // Cellule sélectionnée d'office à l'ouverture (Key.defaultPopupChar) et zone « de départ » (la
    // touche pressée jusqu'à la bulle) où le doigt la conserve : relâcher sans bouger la saisit.
    private var popupDefaultRow = -1
    private var popupDefaultCol = -1
    private val popupHomeRect = RectF()

    val isShown: Boolean get() = key != null

    /** Symbole de la cellule sélectionnée, ou null si le doigt est hors de la bulle. */
    fun selectedSymbol(): Char? = popupGrid.getOrNull(popupSelectedRow)?.getOrNull(popupSelectedCol)

    /**
     * Ouvre la bulle de [pressed] au-dessus de sa position [bounds]. [headroom] : place disponible au-dessus de la vue ;
     * [content] : zone horizontale des touches.
     */
    fun show(pressed: Key, grid: List<List<Char>>, bounds: RectF, headroom: Float, content: KeyboardWidth) {
        val rows = grid.size
        val cols = grid.maxOf { it.size }
        val margin = view.dimen(R.dimen.taipo_popup_margin)
        val gap = view.dimen(R.dimen.taipo_popup_gap)
        val padding = view.dimen(R.dimen.taipo_popup_padding)
        val clipMargin = view.dimen(R.dimen.taipo_popup_clip_margin)
        // Marge verticale un peu plus grande que la marge horizontale : la bulle est plus haute
        // de quelques pixels sans grossir les symboles ni les cellules.
        val paddingV = padding + view.dimen(R.dimen.taipo_popup_padding_extra_vertical)

        // La bulle doit tenir dans l'espace disponible au-dessus de la vue : la hauteur des cellules s'adapte à la place
        // disponible au-dessus de la rangée de la touche.
        val cellMax = view.dimen(R.dimen.taipo_popup_cell_max)
        val cellWidth = minOf(cellMax, (content.widthPx - 2 * margin - 2 * padding) / cols)
        val cellHeight = minOf(cellMax, (bounds.top + headroom - gap - 2 * paddingV) / rows)
            .coerceAtLeast(view.dimen(R.dimen.taipo_popup_cell_min_height))
        val popupWidth = cols * cellWidth + 2 * padding
        val popupHeight = rows * cellHeight + 2 * paddingV

        // Choix présélectionné (Key.defaultPopupChar) : il doit se trouver centré juste au-dessus
        // de la touche pressée, pour que relâcher sans bouger le doigt le saisisse et que le geste
        // vers le haut soit naturel. Sans choix par défaut, c'est la bulle entière qui est centrée.
        var defaultRow = -1
        var defaultCol = -1
        pressed.defaultPopupChar?.let { default ->
            grid.forEachIndexed { r, row ->
                val c = row.indexOf(default)
                if (c >= 0) {
                    defaultRow = r
                    defaultCol = c
                }
            }
        }
        val anchorInPopup = if (defaultCol >= 0) padding + (defaultCol + 0.5f) * cellWidth else popupWidth / 2f
        // Une bulle trop proche d'un bord de l'écran est décalée pour rester visible : le choix
        // présélectionné ne peut alors plus être centré exactement sur la touche.
        val left = PopupPlacement.left(
            keyCenterX = bounds.centerX(),
            anchorInPopup = anchorInPopup,
            popupWidth = popupWidth,
            minLeft = content.leftPx + margin,
            maxRight = content.rightPx - margin,
        )
        val popupTop = (bounds.top - gap - popupHeight).coerceAtLeast(-headroom)

        popupRect.set(left, popupTop, left + popupWidth, popupTop + popupHeight)
        popupCellWidth = cellWidth
        popupCellHeight = cellHeight
        popupPadding = padding
        popupPaddingV = paddingV
        // Zone de départ : la touche et l'espace jusqu'à la bulle, avec une petite tolérance.
        popupHomeRect.set(
            bounds.left - clipMargin, popupRect.bottom, bounds.right + clipMargin, bounds.bottom + clipMargin,
        )
        popupDefaultRow = defaultRow
        popupDefaultCol = defaultCol
        popupSelectedRow = popupDefaultRow
        popupSelectedCol = popupDefaultCol
        popupGrid = grid
        key = pressed
        view.invalidate()
    }

    fun dismiss() {
        key = null
        popupGrid = emptyList()
        popupSelectedRow = -1
        popupSelectedCol = -1
        popupDefaultRow = -1
        popupDefaultCol = -1
        view.invalidate()
    }

    /** Détachement de la vue : la bulle est oubliée sans redessin (comme avant l'extraction). */
    fun forget() {
        key = null
    }

    fun updateSelection(x: Float, y: Float) {
        if (key == null) return
        var row = -1
        var col = -1
        if (popupRect.contains(x, y)) {
            val r = ((y - popupRect.top - popupPaddingV) / popupCellHeight).toInt()
                .coerceIn(0, popupGrid.lastIndex)
            val c = ((x - popupRect.left - popupPadding) / popupCellWidth).toInt()
                .coerceIn(0, popupGrid[r].lastIndex)
            row = r
            col = c
        } else if (popupDefaultRow >= 0 && popupHomeRect.contains(x, y)) {
            // Doigt resté sur la touche d'origine (ou juste au-dessus) : le choix par défaut reste
            // sélectionné, un léger tremblement du doigt ne l'annule donc pas.
            row = popupDefaultRow
            col = popupDefaultCol
        }
        if (row != popupSelectedRow || col != popupSelectedCol) {
            popupSelectedRow = row
            popupSelectedCol = col
            view.invalidate()
        }
    }

    /**
     * Dessine la bulle. [cornerRadius], [textSize], [selectionInset] et [iconSize] sont les dimensions courantes de la vue ;
     * [iconMaxRatio] plafonne l'icône du globe par rapport à la hauteur d'une cellule ; [shifted] : majuscules actives.
     */
    fun draw(
        canvas: Canvas,
        cornerRadius: Float,
        textSize: Float,
        selectionInset: Float,
        iconSize: Float,
        iconMaxRatio: Float,
        shifted: Boolean,
        globeIcon: Drawable,
    ) {
        if (key == null) return
        canvas.drawRoundRect(popupRect, cornerRadius, cornerRadius, popupPaint)

        if (popupTextPaint.textSize != textSize) popupTextPaint.textSize = textSize
        val baselineOffset = -(popupTextPaint.ascent() + popupTextPaint.descent()) / 2f

        popupGrid.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, symbol ->
                val cx = popupRect.left + popupPadding + (colIndex + 0.5f) * popupCellWidth
                val cy = popupRect.top + popupPaddingV + (rowIndex + 0.5f) * popupCellHeight
                if (rowIndex == popupSelectedRow && colIndex == popupSelectedCol) {
                    val selectionRadius = minOf(popupCellWidth, popupCellHeight) / 2f - selectionInset
                    canvas.drawCircle(cx, cy, selectionRadius, popupSelectionPaint)
                }
                if (symbol == GLOBE_SYMBOL) {
                    val half = minOf(iconSize, popupCellHeight * iconMaxRatio) / 2f
                    globeIcon.setBounds(
                        (cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt(),
                    )
                    globeIcon.draw(canvas)
                    return@forEachIndexed
                }
                val shown = if (shifted && symbol.isLetter()) symbol.uppercaseChar() else symbol
                charBuffer[0] = shown
                canvas.drawText(charBuffer, 0, 1, cx, cy + baselineOffset, popupTextPaint)
            }
        }
    }

    companion object {
        /** Caractère fictif (zone d'usage privé) qui représente la touche globe dans la bulle d'Entrée ; jamais saisi. */
        const val GLOBE_SYMBOL = '\uE000'

        /** Grille de la bulle d'Entrée : une seule cellule, le globe. */
        val ENTER_GRID: List<List<Char>> = listOf(listOf(GLOBE_SYMBOL))
    }
}
