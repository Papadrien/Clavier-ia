package fr.junade.taipo

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Rôle d'un enfant dans une [SpaceBetweenRow]. */
internal enum class RowRole {
    /** Largeur fixe (pilule icône + texte, emplacement réservé). */
    FIXED,

    /** Bouton rond : garde sa largeur, ne rétrécit (un peu) que si la rangée est trop étroite. */
    ROUND,

    /** Largeur du contenu, rétrécie en premier si la rangée est trop étroite (puce de collage). */
    SHRINK,

    /** Prend toute la place restante entre ses voisins (bande de mots) ; les écarts valent alors l'écart minimal. */
    FILL,
}

/**
 * Répartition horizontale « espace entre » : le premier élément est collé à gauche, le dernier à droite, et l'espace restant
 * est réparti également entre les éléments. Calcul pur (en pixels), testé en JVM.
 */
internal object SpaceBetween {

    class Result(val widths: IntArray, val lefts: IntArray)

    /** Un bouton rond ne rétrécit pas sous cette part de sa largeur d'origine. */
    private const val ROUND_FLOOR_NUMERATOR = 3
    private const val ROUND_FLOOR_DENOMINATOR = 4

    /**
     * [natural] : largeur souhaitée de chaque élément (ignorée pour un élément [RowRole.FILL]) ; [roles] : leur rôle ;
     * [minGap] : écart minimal entre deux éléments ; [fillGap] : écart quand un élément [RowRole.FILL] occupe la rangée.
     * Renvoie la largeur et la position (depuis le bord gauche) de chacun.
     */
    fun distribute(available: Int, natural: List<Int>, roles: List<RowRole>, minGap: Int, fillGap: Int = minGap): Result {
        val count = natural.size
        val widths = natural.toIntArray()
        val lefts = IntArray(count)
        if (count == 0) return Result(widths, lefts)
        val gaps = count - 1
        val fill = roles.indexOf(RowRole.FILL)
        if (fill >= 0) widths[fill] = 0
        val edge = if (fill >= 0) fillGap else minGap
        var overflow = widths.sum() + gaps * edge - available
        if (overflow > 0) overflow = shrinkChip(widths, roles, overflow)
        if (overflow > 0) shrinkRound(widths, natural, roles, overflow)
        val total = widths.sum()
        val gap: Float
        if (fill >= 0) {
            val rest = available - total - gaps * fillGap
            if (rest > 0) widths[fill] = rest
            gap = fillGap.toFloat()
        } else {
            gap = if (gaps > 0) ((available - total).toFloat() / gaps).coerceAtLeast(0f) else 0f
        }
        var x = 0f
        for (i in 0 until count) {
            lefts[i] = Math.round(x)
            x += widths[i] + gap
        }
        // Sans élément extensible, le dernier élément touche exactement le bord droit (pas d'erreur d'arrondi).
        if (fill < 0 && gaps > 0 && widths.sum() + gaps * minGap <= available) lefts[count - 1] = available - widths[count - 1]
        return Result(widths, lefts)
    }

    private fun shrinkChip(widths: IntArray, roles: List<RowRole>, overflow: Int): Int {
        var left = overflow
        for (i in widths.indices) {
            if (roles[i] != RowRole.SHRINK || left <= 0) continue
            val taken = minOf(left, widths[i])
            widths[i] -= taken
            left -= taken
        }
        return left
    }

    private fun shrinkRound(widths: IntArray, natural: List<Int>, roles: List<RowRole>, overflow: Int) {
        val rounds = widths.indices.filter { roles[it] == RowRole.ROUND }
        if (rounds.isEmpty()) return
        var left = overflow
        // Retrait égal par bouton, limité par le plancher ; on recommence tant qu'il reste du retrait possible.
        while (left > 0) {
            val room = rounds.filter { widths[it] > natural[it] * ROUND_FLOOR_NUMERATOR / ROUND_FLOOR_DENOMINATOR }
            if (room.isEmpty()) return
            val share = maxOf(1, left / room.size)
            for (i in room) {
                if (left <= 0) break
                val floor = natural[i] * ROUND_FLOOR_NUMERATOR / ROUND_FLOOR_DENOMINATOR
                val taken = minOf(share, widths[i] - floor, left)
                widths[i] -= taken
                left -= taken
            }
        }
    }
}

/**
 * Rangée de la barre du haut : premier bouton à gauche, dernier à droite, espace restant réparti également
 * ([SpaceBetween]). [reservedEndPx] réserve à droite la place d'un bouton posé par-dessus (Vocal) qui compte comme un
 * élément de la répartition. Un bouton qui change de place (apparition de Corriger, par exemple) glisse en ressort jusqu'à
 * sa nouvelle position ([BarReveal.glideX]) au lieu de sauter.
 */
internal class SpaceBetweenRow(context: Context) : ViewGroup(context) {

    class LayoutParams(width: Int, height: Int, val role: RowRole = RowRole.ROUND) : ViewGroup.LayoutParams(width, height)

    /** Largeur réservée à droite pour le bouton Vocal, posé par la barre au-dessus des rangées. */
    var reservedEndPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Écart minimal entre deux éléments. */
    var minGapPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Écart entre deux éléments quand la rangée contient un élément extensible ([RowRole.FILL]). */
    var fillGapPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private var items: List<View> = emptyList()
    private var lefts = IntArray(0)
    private val lastLeft = HashMap<View, Int>()
    private var lastWidth = -1

    init {
        clipToPadding = false
        clipChildren = false
    }

    /** À appeler quand la rangée est masquée : à sa réapparition, aucun bouton ne glisse depuis une position périmée. */
    fun forgetPositions() {
        lastLeft.clear()
    }

    private fun roleOf(view: View): RowRole {
        if (view is LabeledIconButton && view.labeled) return RowRole.FIXED
        return (view.layoutParams as? LayoutParams)?.role ?: RowRole.ROUND
    }

    private fun naturalWidth(view: View, height: Int): Int {
        if (view is LabeledIconButton && view.labeled) return view.labeledWidth()
        val params = view.layoutParams
        if (params.width >= 0) return params.width
        view.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        return view.measuredWidth
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val visible = ArrayList<View>()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != GONE) visible.add(child)
        }
        val natural = ArrayList<Int>()
        val roles = ArrayList<RowRole>()
        for (child in visible) {
            natural.add(naturalWidth(child, childHeight))
            roles.add(roleOf(child))
        }
        if (reservedEndPx > 0) {
            natural.add(reservedEndPx)
            roles.add(RowRole.FIXED)
        }
        val inner = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val result = SpaceBetween.distribute(inner, natural, roles, minGapPx, if (fillGapPx > 0) fillGapPx else minGapPx)
        visible.forEachIndexed { index, child ->
            child.measure(
                MeasureSpec.makeMeasureSpec(result.widths[index], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }
        items = visible
        lefts = result.lefts
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val widthChanged = (r - l) != lastWidth
        lastWidth = r - l
        val animate = !widthChanged && isLaidOut && ValueAnimator.areAnimatorsEnabled()
        val seen = HashSet<View>()
        items.forEachIndexed { index, child ->
            val left = paddingLeft + lefts[index]
            child.layout(left, paddingTop, left + child.measuredWidth, paddingTop + child.measuredHeight)
            seen.add(child)
            val previous = lastLeft[child]
            val role = (child.layoutParams as? LayoutParams)?.role
            if (animate && previous != null && previous != left && role != RowRole.FILL && !BarReveal.isEntering(child)) {
                BarReveal.glideX(child, previous + child.translationX - left)
            }
            lastLeft[child] = left
        }
        lastLeft.keys.retainAll(seen)
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams = LayoutParams(p.width, p.height)
}
