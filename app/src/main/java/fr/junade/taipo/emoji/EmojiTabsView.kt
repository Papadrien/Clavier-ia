package fr.junade.taipo.emoji

import fr.junade.taipo.R
import fr.junade.taipo.dimen
import fr.junade.taipo.themeColor
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * Story 1.15 : rangée d'onglets des catégories d'emojis (un emoji par catégorie). Touche brève sur un
 * onglet = saut à la catégorie correspondante.
 *
 * Lot 14 : l'onglet sélectionné est une pastille violette « face + ombre » (même géométrie que les touches
 * du clavier), les autres sont atténués. Les glyphes restent du texte (aucune image), dessinés au Canvas ;
 * rien n'est alloué dans [onDraw].
 */
@SuppressLint("ViewConstructor")
class EmojiTabsView(context: Context) : View(context) {

    fun interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private var icons: List<String> = emptyList()

    /** Lot 20 : noms des catégories, lus par TalkBack à la place du glyphe (même ordre que [icons]). */
    private var titles: List<String> = emptyList()
    private var listener: OnTabSelectedListener? = null

    // Lot 20 : un bouton virtuel par onglet, avec l'état « sélectionné » (TalkBack).
    private val accessibility = TabsAccessibility()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        defaultFocusHighlightEnabled = false
    }

    var selectedIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            accessibility.invalidateRoot()
            invalidate()
        }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.emoji_tab_selected) }
    private val pillShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.emoji_tab_selected_shadow)
    }
    private val dividerPaint = Paint().apply { color = context.themeColor(R.color.emoji_tab_divider) }
    private val pillRect = RectF()

    /** [newTitles] : noms des catégories pour TalkBack (le glyphe seul, sinon). */
    fun setTabs(newIcons: List<String>, newTitles: List<String> = emptyList()) {
        icons = newIcons
        titles = newTitles
        selectedIndex = 0
        accessibility.invalidateRoot()
        invalidate()
    }

    fun setOnTabSelectedListener(newListener: OnTabSelectedListener) {
        listener = newListener
    }

    override fun onDraw(canvas: Canvas) {
        if (icons.isEmpty()) return
        val tabWidth = width.toFloat() / icons.size
        val dividerHeight = dimen(R.dimen.taipo_emoji_tab_divider_thickness)
        drawSelectedPill(canvas, tabWidth, dividerHeight)
        iconPaint.textSize = minOf(height * 0.5f, dimen(R.dimen.taipo_emoji_tab_icon_size))
        val baseline = height / 2f - (iconPaint.ascent() + iconPaint.descent()) / 2f - dp(1f)
        icons.forEachIndexed { index, icon ->
            iconPaint.alpha = if (index == selectedIndex) 255 else UNSELECTED_ALPHA
            canvas.drawText(icon, tabWidth * (index + 0.5f), baseline, iconPaint)
        }
        canvas.drawRect(0f, height - dividerHeight, width.toFloat(), height.toFloat(), dividerPaint)
    }

    /**
     * Pastille de l'onglet sélectionné : une ombre décalée vers le bas sous une face, comme les touches du
     * clavier. Le rayon est plafonné à la moitié de la plus petite dimension de la face (pastille arrondie
     * plutôt que rectangle aux angles aberrants si l'onglet est étroit).
     */
    private fun drawSelectedPill(canvas: Canvas, tabWidth: Float, dividerHeight: Float) {
        val inset = dimen(R.dimen.taipo_emoji_tab_pill_inset)
        val shadow = dimen(R.dimen.taipo_key_shadow_height)
        val left = tabWidth * selectedIndex + inset
        val right = tabWidth * (selectedIndex + 1) - inset
        val top = inset
        val faceBottom = height - dividerHeight - inset - shadow
        if (right <= left || faceBottom <= top) return
        val radius = minOf(dimen(R.dimen.taipo_key_corner_radius), (right - left) / 2f, (faceBottom - top) / 2f)
        pillRect.set(left, top + shadow, right, faceBottom + shadow)
        canvas.drawRoundRect(pillRect, radius, radius, pillShadowPaint)
        pillRect.set(left, top, right, faceBottom)
        canvas.drawRoundRect(pillRect, radius, radius, pillPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (icons.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    val index = (event.x / (width.toFloat() / icons.size)).toInt().coerceIn(0, icons.lastIndex)
                    listener?.onTabSelected(index)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    /** Lot 20 : l'exploration au doigt de TalkBack arrive en événements de survol, relayés à l'arbre virtuel. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private inner class TabsAccessibility : ExploreByTouchHelper(this@EmojiTabsView) {

        private fun title(index: Int): String = titles.getOrNull(index) ?: icons.getOrNull(index).orEmpty()

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (icons.isEmpty() || x < 0f || x >= width || y < 0f || y >= height) return ExploreByTouchHelper.INVALID_ID
            return (x / (width.toFloat() / icons.size)).toInt().coerceIn(0, icons.lastIndex)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (index in icons.indices) virtualViewIds.add(index)
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            if (virtualViewId !in icons.indices) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            val tabWidth = width.toFloat() / icons.size
            node.contentDescription = title(virtualViewId)
            node.setBoundsInParent(Rect((tabWidth * virtualViewId).toInt(), 0, (tabWidth * (virtualViewId + 1)).toInt(), height))
            node.className = android.widget.Button::class.java.name
            node.isSelected = virtualViewId == selectedIndex
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK || virtualViewId !in icons.indices) return false
            listener?.onTabSelected(virtualViewId)
            return true
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
            event.contentDescription = title(virtualViewId)
        }
    }

    private companion object {
        /** Opacité des glyphes des onglets non sélectionnés (0 à 255). */
        const val UNSELECTED_ALPHA = 140
    }
}
