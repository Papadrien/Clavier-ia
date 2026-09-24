package fr.papadrien.clavier

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    fun interface OnKeyListener {
        fun onKey(key: Key)
    }

    private var keyListener: OnKeyListener? = null
    private var pressedKey: Key? = null

    var layout: KeyboardLayout = Keyboards.letters
        set(value) {
            field = value
            invalidate()
        }

    var isShifted: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val backgroundColor = Color.parseColor("#1E1E1E")
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B2B2B") }
    private val functionalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3F47") }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4E5768") }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A7FD4") }
    private val spaceBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A4A4A")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val keyRect = RectF()

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            pressedKey?.let { key ->
                if (key.action is KeyAction.Backspace) {
                    keyListener?.onKey(key)
                    repeatHandler.postDelayed(this, 60L)
                }
            }
        }
    }

    fun setOnKeyListener(listener: OnKeyListener) {
        keyListener = listener
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeatHandler.removeCallbacksAndMessages(null)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)

        val inset = insetPx()
        val rowHeight = height / layout.rows.size.toFloat()

        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.fold(0f) { acc, key -> acc + key.weight }
            val top = rowIndex * rowHeight
            var left = 0f

            row.forEach { key ->
                val keyWidth = width * key.weight / totalWeight
                drawKey(canvas, key, left, top, keyWidth, rowHeight, inset)
                left += keyWidth
            }
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, left: Float, top: Float, keyWidth: Float, rowHeight: Float, inset: Float) {
        keyRect.set(left + inset, top + inset, left + keyWidth - inset, top + rowHeight - inset)

        val fill = when {
            key == pressedKey -> pressedPaint
            key.action is KeyAction.Shift && isShifted -> activePaint
            key.action is KeyAction.ToggleLayout && layout.id == LayoutId.SYMBOLS -> activePaint
            isFunctional(key) -> functionalPaint
            else -> normalPaint
        }
        canvas.drawRoundRect(keyRect, cornerRadiusPx(), cornerRadiusPx(), fill)

        when (key.action) {
            KeyAction.Space -> {
                val cx = keyRect.centerX()
                val cy = keyRect.centerY()
                canvas.drawLine(
                    cx - spaceBarLengthPx(), cy, cx + spaceBarLengthPx(), cy, spaceBarPaint,
                )
            }

            else -> {
                val label = displayLabel(key)
                if (label.isNotEmpty()) {
                    autoSizeTextPaint(key)
                    val baseline = keyRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                    canvas.drawText(label, keyRect.centerX(), baseline, textPaint)
                }
            }
        }
    }

    private fun autoSizeTextPaint(key: Key) {
        val defaultSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics,
        )
        val bigSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics,
        )
        if (key.id == "backspace" || key.id == "enter" || key.id == "shift" || key.id == "toggle") {
            textPaint.textSize = bigSize
        } else {
            textPaint.textSize = defaultSize
        }
    }

    private fun displayLabel(key: Key): String = when (val action = key.action) {
        is KeyAction.TypeChar ->
            if (isShifted && action.char.isLetter()) action.char.uppercaseChar().toString() else key.label

        KeyAction.Shift -> "⇧"
        KeyAction.Backspace -> "⌫"
        KeyAction.Enter -> "⏎"
        KeyAction.Space -> ""
        KeyAction.ToggleLayout -> if (layout.id == LayoutId.LETTERS) "123" else "ABC"
    }

    private fun isFunctional(key: Key): Boolean = when (key.action) {
        is KeyAction.TypeChar, KeyAction.Space -> false
        KeyAction.Shift, KeyAction.Backspace, KeyAction.Enter, KeyAction.ToggleLayout -> true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = keyAt(event.x, event.y)
                startRepeat(pressedKey)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val current = keyAt(event.x, event.y)
                if (current != pressedKey) {
                    pressedKey = current
                    startRepeat(current)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                stopRepeat()
                val key = pressedKey
                pressedKey = null
                if (key != null && keyAt(event.x, event.y) == key) {
                    keyListener?.onKey(key)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                stopRepeat()
                pressedKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val rows = layout.rows
        if (rows.isEmpty()) return null
        val rowHeight = height / rows.size.toFloat()
        val rowIndex = (y / rowHeight).toInt().coerceIn(0, rows.lastIndex)
        val row = rows[rowIndex]
        val totalWeight = row.fold(0f) { acc, key -> acc + key.weight }
        var acc = 0f
        for (key in row) {
            acc += width * key.weight / totalWeight
            if (x < acc) return key
        }
        return row.last()
    }

    private fun startRepeat(key: Key?) {
        stopRepeat()
        if (key?.action is KeyAction.Backspace) {
            repeatHandler.postDelayed(repeatRunnable, 400L)
        }
    }

    private fun stopRepeat() {
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    private fun insetPx(): Float = dp(4f)
    private fun cornerRadiusPx(): Float = dp(7f)
    private fun spaceBarLengthPx(): Float = dp(22f)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}