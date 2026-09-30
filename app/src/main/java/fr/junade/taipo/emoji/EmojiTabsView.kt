package fr.junade.taipo.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Story 1.15 : rangée d'onglets des catégories d'emojis (un emoji par catégorie), l'onglet
 * sélectionné étant souligné. Touche brève sur un onglet = saut à la catégorie correspondante.
 */
@SuppressLint("ViewConstructor")
class EmojiTabsView(context: Context) : View(context) {

    fun interface OnTabSelectedListener {
        fun onTabSelected(index: Int)
    }

    private var icons: List<String> = emptyList()
    private var listener: OnTabSelectedListener? = null

    var selectedIndex: Int = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80CBC4") }
    private val dividerPaint = Paint().apply { color = Color.parseColor("#2E2E2E") }

    fun setTabs(newIcons: List<String>) {
        icons = newIcons
        selectedIndex = 0
        invalidate()
    }

    fun setOnTabSelectedListener(newListener: OnTabSelectedListener) {
        listener = newListener
    }

    override fun onDraw(canvas: Canvas) {
        if (icons.isEmpty()) return
        val tabWidth = width.toFloat() / icons.size
        iconPaint.textSize = minOf(height * 0.5f, dp(22f))
        val baseline = height / 2f - (iconPaint.ascent() + iconPaint.descent()) / 2f - dp(1f)
        icons.forEachIndexed { index, icon ->
            iconPaint.alpha = if (index == selectedIndex) 255 else 140
            canvas.drawText(icon, tabWidth * (index + 0.5f), baseline, iconPaint)
        }
        val indicatorWidth = tabWidth * 0.6f
        val left = tabWidth * (selectedIndex + 0.5f) - indicatorWidth / 2f
        canvas.drawRect(left, height - dp(3f), left + indicatorWidth, height.toFloat(), indicatorPaint)
        canvas.drawRect(0f, height - dp(0.5f), width.toFloat(), height.toFloat(), dividerPaint)
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
