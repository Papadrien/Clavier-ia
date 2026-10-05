package fr.junade.taipo.emoji

import fr.junade.taipo.R
import fr.junade.taipo.TaipoType
import fr.junade.taipo.dimen
import fr.junade.taipo.themeColor
import fr.junade.taipo.useTaipoFont
import fr.junade.taipo.fixedHeightTextDimen
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.abs

/**
 * Story 1.15 : grille défilante des emojis, toutes catégories à la suite, chacune précédée de son
 * titre (comme sur Gboard). Dessinée à la main, comme le clavier : seuls les emojis visibles sont
 * dessinés, ce qui reste fluide avec les ~1 700 emojis du catalogue. La disposition est calculée
 * par [EmojiGridLayout] (logique pure, testée en JVM).
 *
 * Lot 14 : couleurs, tailles et espacements viennent de la charte (colors.xml, dimens.xml) ; les titres sont
 * en Open Sans ; les emojis restent des glyphes de texte (aucune image).
 */
@SuppressLint("ViewConstructor")
class EmojiGridView(context: Context) : View(context) {

    fun interface OnEmojiSelectedListener {
        fun onEmojiSelected(emoji: String)
    }

    /** Appelé quand la section affichée en haut change (met à jour l'onglet sélectionné). */
    fun interface OnSectionChangedListener {
        fun onSectionChanged(index: Int)
    }

    private var emojiListener: OnEmojiSelectedListener? = null
    private var sectionListener: OnSectionChangedListener? = null

    private var sections: List<EmojiSection> = emptyList()
    private var gridLayout: EmojiGridLayout? = null

    /** Décalage vertical du contenu (0 = haut de la première section). */
    private var offsetY = 0f
    private var pendingSection = -1
    private var lastReportedSection = -1

    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val viewConfiguration = ViewConfiguration.get(context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maxFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity

    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedHit: EmojiHit? = null

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.emoji_header_text)
        textSize = fixedHeightTextDimen(R.dimen.taipo_emoji_header_text_size)
        useTaipoFont(context, TaipoType.Weight.MEDIUM)
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.emoji_header_text)
        textSize = fixedHeightTextDimen(R.dimen.taipo_emoji_message_text_size)
        useTaipoFont(context)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.emoji_cell_pressed) }
    private val textMargin = dimen(R.dimen.taipo_bar_padding_horizontal)
    private val pressedInset = dimen(R.dimen.taipo_emoji_cell_pressed_inset)
    private val pressedRadius = dimen(R.dimen.taipo_key_corner_radius)
    private val pressedRect = RectF()

    private var drawCanvas: Canvas? = null

    // Lot 20 : arbre d'accessibilité virtuel (TalkBack) des emojis visibles, voir EmojiGridAccessibility.
    private val accessibility = EmojiGridAccessibility()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        defaultFocusHighlightEnabled = false
    }

    private val visitor = object : EmojiGridLayout.Visitor {
        override fun header(section: Int, y: Float) {
            val canvas = drawCanvas ?: return
            val g = gridLayout ?: return
            val baseline = y + g.headerHeight / 2f - (headerPaint.ascent() + headerPaint.descent()) / 2f
            canvas.drawText(sections[section].title, textMargin, baseline, headerPaint)
        }

        override fun message(section: Int, y: Float) {
            val canvas = drawCanvas ?: return
            val g = gridLayout ?: return
            val text = sections[section].emptyMessage ?: return
            val baseline = y + g.cellSize / 2f - (messagePaint.ascent() + messagePaint.descent()) / 2f
            canvas.drawText(text, textMargin, baseline, messagePaint)
        }

        override fun cell(emoji: String, x: Float, y: Float) {
            val canvas = drawCanvas ?: return
            val g = gridLayout ?: return
            val pressed = pressedHit
            if (pressed != null && pressed.left == x && pressed.top == y) {
                val inset = pressedInset
                pressedRect.set(x + inset, y + inset, x + g.cellSize - inset, y + g.cellSize - inset)
                canvas.drawRoundRect(pressedRect, pressedRadius, pressedRadius, pressedPaint)
            }
            val baseline = y + g.cellSize / 2f - (emojiPaint.ascent() + emojiPaint.descent()) / 2f
            canvas.drawText(emoji, x + g.cellSize / 2f, baseline, emojiPaint)
        }
    }

    fun setOnEmojiSelectedListener(listener: OnEmojiSelectedListener) {
        emojiListener = listener
    }

    fun setOnSectionChangedListener(listener: OnSectionChangedListener) {
        sectionListener = listener
    }

    /** Remplace le contenu de la grille ; le défilement revient en haut. */
    fun setSections(newSections: List<EmojiSection>) {
        sections = newSections
        scroller.forceFinished(true)
        offsetY = 0f
        lastReportedSection = -1
        rebuildLayout()
        accessibility.invalidateRoot()
    }

    /** Place le haut d'une section en haut de la grille (immédiatement, ou avec une animation). */
    fun showSection(index: Int, animate: Boolean) {
        val g = gridLayout
        if (g == null) {
            pendingSection = index
            return
        }
        if (index !in sections.indices) return
        val target = g.sectionTop(index).coerceIn(0f, maxScroll())
        scroller.forceFinished(true)
        if (animate) {
            scroller.startScroll(0, offsetY.toInt(), 0, (target - offsetY).toInt(), SCROLL_ANIMATION_MS)
            postInvalidateOnAnimation()
        } else {
            offsetY = target
            reportSection()
            invalidate()
            accessibility.invalidateRoot()
        }
    }

    /** Lot 20 : l'exploration au doigt de TalkBack arrive en événements de survol, relayés à l'arbre virtuel. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * Lot 20 : défilement demandé par TalkBack (balayage à deux doigts, ou action « défiler ») : une page à la fois,
     * avec un léger recouvrement. [direction] vaut 1 (vers le bas) ou -1 (vers le haut) ; faux si on est déjà au bout.
     */
    private fun scrollByPage(direction: Int): Boolean {
        val target = (offsetY + direction * height * PAGE_SCROLL_RATIO).coerceIn(0f, maxScroll())
        if (target == offsetY) return false
        scroller.forceFinished(true)
        offsetY = target
        reportSection()
        invalidate()
        accessibility.invalidateRoot()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
        accessibility.invalidateRoot()
    }

    private fun rebuildLayout() {
        if (width <= 0 || sections.isEmpty()) {
            gridLayout = null
            invalidate()
            return
        }
        val columns = EmojiGridLayout.columnsFor(width.toFloat(), dimen(R.dimen.taipo_emoji_cell_min_size))
        val cell = width.toFloat() / columns
        gridLayout = EmojiGridLayout(sections, columns, cell, dimen(R.dimen.taipo_emoji_header_height))
        emojiPaint.textSize = cell * EMOJI_TEXT_RATIO
        if (pendingSection >= 0) {
            val index = pendingSection
            pendingSection = -1
            val g = gridLayout
            if (g != null && index in sections.indices) offsetY = g.sectionTop(index)
        }
        offsetY = offsetY.coerceIn(0f, maxScroll())
        reportSection()
        invalidate()
    }

    private fun maxScroll(): Float = gridLayout?.maxScroll(height.toFloat()) ?: 0f

    private fun currentSection(): Int {
        val g = gridLayout ?: return 0
        if (sections.isEmpty()) return 0
        val max = maxScroll()
        // Tout en bas, la dernière section est la « courante » même si elle est trop courte pour atteindre le haut.
        if (max > 0f && offsetY >= max - 1f) return sections.lastIndex
        return g.sectionAt(offsetY + 1f).coerceAtLeast(0)
    }

    private fun reportSection() {
        val index = currentSection()
        if (index != lastReportedSection) {
            lastReportedSection = index
            sectionListener?.onSectionChanged(index)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val g = gridLayout ?: return
        canvas.save()
        canvas.translate(0f, -offsetY)
        drawCanvas = canvas
        g.forEachVisible(offsetY, offsetY + height, visitor)
        drawCanvas = null
        canvas.restore()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offsetY = scroller.currY.toFloat().coerceIn(0f, maxScroll())
            reportSection()
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = gridLayout ?: return false
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downY = event.y
                lastY = event.y
                dragging = false
                pressedHit = g.hitTest(event.x, event.y + offsetY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > touchSlop) {
                    dragging = true
                    pressedHit = null
                }
                if (dragging) {
                    offsetY = (offsetY - (event.y - lastY)).coerceIn(0f, maxScroll())
                    reportSection()
                }
                lastY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val velocity = tracker.yVelocity
                    if (abs(velocity) > minFlingVelocity) {
                        scroller.fling(0, offsetY.toInt(), 0, (-velocity).toInt(), 0, 0, 0, maxScroll().toInt())
                        postInvalidateOnAnimation()
                    }
                } else {
                    val hit = pressedHit
                    if (hit != null && g.hitTest(event.x, event.y + offsetY)?.emoji == hit.emoji) {
                        emojiListener?.onEmojiSelected(hit.emoji)
                    }
                }
                finishTouch()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                finishTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finishTouch() {
        pressedHit = null
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scroller.forceFinished(true)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * Lot 20 : les emojis visibles (et le message « aucun récent ») sont exposés à TalkBack comme des boutons virtuels, dans
     * l'ordre de lecture ; un double appui saisit l'emoji. La grille se défile par les actions de défilement de TalkBack.
     * Le contenu de l'emoji est annoncé par TalkBack lui-même (nom de l'emoji dans la langue du système).
     */
    private inner class EmojiGridAccessibility : ExploreByTouchHelper(this@EmojiGridView) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val g = gridLayout ?: return ExploreByTouchHelper.INVALID_ID
            val contentY = y + offsetY
            g.locate(x, contentY)?.let { return it.section * ID_STRIDE + it.index }
            // Message d'une section vide (« aucun récent »).
            val section = g.sectionAt(contentY)
            if (section >= 0 && sections[section].emojis.isEmpty() && sections[section].emptyMessage != null) {
                val top = g.sectionTop(section) + g.headerHeight
                if (contentY >= top && contentY < top + g.cellSize) return section * ID_STRIDE + MESSAGE_INDEX
            }
            return ExploreByTouchHelper.INVALID_ID
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            val g = gridLayout ?: return
            g.forEachVisible(
                offsetY,
                offsetY + height,
                object : EmojiGridLayout.Visitor {
                    override fun header(section: Int, y: Float) = Unit

                    override fun message(section: Int, y: Float) {
                        virtualViewIds.add(section * ID_STRIDE + MESSAGE_INDEX)
                    }

                    override fun cell(emoji: String, x: Float, y: Float) = Unit

                    override fun cellAt(section: Int, index: Int, emoji: String, x: Float, y: Float) {
                        virtualViewIds.add(section * ID_STRIDE + index)
                    }
                },
            )
        }

        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val g = gridLayout
            val section = sections.getOrNull(virtualViewId / ID_STRIDE)
            val index = virtualViewId % ID_STRIDE
            if (g == null || section == null) {
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 1, 1))
                return
            }
            val bodyTop = g.sectionTop(virtualViewId / ID_STRIDE) + g.headerHeight - offsetY
            if (index == MESSAGE_INDEX) {
                node.contentDescription = section.emptyMessage.orEmpty()
                node.setBoundsInParent(Rect(0, bodyTop.toInt(), width, (bodyTop + g.cellSize).toInt()))
                node.className = android.widget.TextView::class.java.name
                return
            }
            val row = index / g.columns
            val left = (index % g.columns) * g.cellSize
            val top = bodyTop + row * g.cellSize
            node.contentDescription = section.emojis.getOrNull(index).orEmpty()
            node.setBoundsInParent(Rect(left.toInt(), top.toInt(), (left + g.cellSize).toInt(), (top + g.cellSize).toInt()))
            node.className = android.widget.Button::class.java.name
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            if (virtualViewId % ID_STRIDE == MESSAGE_INDEX) return false
            val emoji = sections.getOrNull(virtualViewId / ID_STRIDE)?.emojis?.getOrNull(virtualViewId % ID_STRIDE) ?: return false
            emojiListener?.onEmojiSelected(emoji)
            return true
        }

        override fun onPopulateEventForVirtualView(virtualViewId: Int, event: android.view.accessibility.AccessibilityEvent) {
            val section = sections.getOrNull(virtualViewId / ID_STRIDE)
            val index = virtualViewId % ID_STRIDE
            event.contentDescription = when {
                section == null -> ""
                index == MESSAGE_INDEX -> section.emptyMessage.orEmpty()
                else -> section.emojis.getOrNull(index).orEmpty()
            }
        }

        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.isScrollable = true
            if (offsetY > 0f) info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
            if (offsetY < maxScroll()) info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
            AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD -> scrollByPage(1)
            AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD -> scrollByPage(-1)
            else -> super.performAccessibilityAction(host, action, args)
        }
    }

    companion object {
        /** Taille du texte d'un emoji par rapport au côté de sa cellule. */
        private const val EMOJI_TEXT_RATIO = 0.55f
        private const val SCROLL_ANIMATION_MS = 250

        /** Lot 20 : part de la hauteur de la grille défilée par une action de défilement de TalkBack. */
        private const val PAGE_SCROLL_RATIO = 0.8f

        /** Lot 20 : identifiant virtuel d'un emoji = section × ce pas + rang dans la section (< 100 000 emojis par section). */
        private const val ID_STRIDE = 100_000

        /** Lot 20 : rang réservé au message « aucun récent » d'une section vide. */
        private const val MESSAGE_INDEX = ID_STRIDE - 1
    }
}
