package fr.junade.taipo.emoji

import fr.junade.taipo.useTaipoFont
import fr.junade.taipo.themeColor
import fr.junade.taipo.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs

/**
 * Story 1.15 : grille défilante des emojis, toutes catégories à la suite, chacune précédée de son
 * titre (comme sur Gboard). Dessinée à la main, comme le clavier : seuls les emojis visibles sont
 * dessinés, ce qui reste fluide avec les ~1 700 emojis du catalogue. La disposition est calculée
 * par [EmojiGridLayout] (logique pure, testée en JVM).
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
        color = context.themeColor(R.color.key_hint)
        textSize = sp(13f)
        useTaipoFont(context)
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.key_hint)
        textSize = sp(14f)
        useTaipoFont(context)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_popup) }
    private val pressedRect = RectF()

    private var drawCanvas: Canvas? = null

    private val visitor = object : EmojiGridLayout.Visitor {
        override fun header(section: Int, y: Float) {
            val canvas = drawCanvas ?: return
            val g = gridLayout ?: return
            val baseline = y + g.headerHeight / 2f - (headerPaint.ascent() + headerPaint.descent()) / 2f
            canvas.drawText(sections[section].title, dp(12f), baseline, headerPaint)
        }

        override fun message(section: Int, y: Float) {
            val canvas = drawCanvas ?: return
            val g = gridLayout ?: return
            val text = sections[section].emptyMessage ?: return
            val baseline = y + g.cellSize / 2f - (messagePaint.ascent() + messagePaint.descent()) / 2f
            canvas.drawText(text, dp(12f), baseline, messagePaint)
        }

        override fun cell(emoji: String, x: Float, y: Float) {
            val canvas = drawCanvas ?: return
            val g = gridLayout ?: return
            val pressed = pressedHit
            if (pressed != null && pressed.left == x && pressed.top == y) {
                val inset = dp(2f)
                pressedRect.set(x + inset, y + inset, x + g.cellSize - inset, y + g.cellSize - inset)
                canvas.drawRoundRect(pressedRect, dp(8f), dp(8f), pressedPaint)
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
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    private fun rebuildLayout() {
        if (width <= 0 || sections.isEmpty()) {
            gridLayout = null
            invalidate()
            return
        }
        val columns = EmojiGridLayout.columnsFor(width.toFloat(), dp(MIN_CELL_DP))
        val cell = width.toFloat() / columns
        gridLayout = EmojiGridLayout(sections, columns, cell, dp(HEADER_DP))
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        /** Côté minimal d'une cellule : détermine le nombre de colonnes (9 sur un écran de 412 dp). */
        private const val MIN_CELL_DP = 42f
        private const val HEADER_DP = 30f

        /** Taille du texte d'un emoji par rapport au côté de sa cellule. */
        private const val EMOJI_TEXT_RATIO = 0.55f
        private const val SCROLL_ANIMATION_MS = 250
    }
}
