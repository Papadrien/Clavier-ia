package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Racine de la vue du clavier : porte la « carte » (barre du haut, touches, panneaux, zone de chat : tout le clavier).
 *
 * - Ancré (par défaut) : la carte prend toute la largeur, la racine prend sa hauteur. Rien ne change par rapport à avant.
 * - Flottant ([floating]) : la fenêtre du clavier couvre l'écran (voir `TaipoIme.applyFloatingWindowLayout`), la racine
 *   aussi ; la carte, réduite à [widthPercent] de la largeur utile, est placée à [position] ([FloatingPlacement]), hors des
 *   barres système. Coins arrondis et ombre. Seule la carte reçoit les touchers (`TaipoIme.onComputeInsets`).
 *
 * La poignée ([handle], sous les touches, visible en flottant seulement) déplace la carte ; la nouvelle position est confiée à
 * [onPositionChanged] au lever du doigt. Glissée nettement sous le bas de l'écran, la carte s'estompe et, au lever du doigt,
 * [onDockRequested] recolle le clavier.
 */
class FloatingKeyboardFrame(context: Context, private val card: View) : FrameLayout(context) {

    /** Poignée de déplacement : à placer dans la carte, sous les touches. */
    val handle: View = HandleView(context)

    var floating = false
        set(value) {
            if (field == value) return
            field = value
            handle.visibility = if (value) View.VISIBLE else View.GONE
            (card.background as? KeyboardBackgroundDrawable)?.cornerRadius = if (value) cornerRadiusPx else 0f
            card.elevation = if (value) elevationPx else 0f
            requestLayout()
        }

    var widthPercent: Int = FloatingWidth.DEFAULT_PERCENT
        set(value) {
            val clamped = FloatingWidth.clamp(value)
            if (field == clamped) return
            field = clamped
            requestLayout()
        }

    var position: FloatingPosition = FloatingPosition.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** Fin d'un déplacement par la poignée : la position à retenir. */
    var onPositionChanged: ((FloatingPosition) -> Unit)? = null

    /** La poignée a été lâchée après avoir poussé la carte sous le bas de l'écran : le clavier se recolle. */
    var onDockRequested: (() -> Unit)? = null

    private val cornerRadiusPx = resources.getDimension(R.dimen.taipo_floating_corner_radius)
    private val elevationPx = resources.getDimension(R.dimen.taipo_floating_elevation)
    private val screenMarginPx = resources.getDimensionPixelSize(R.dimen.taipo_floating_screen_margin)
    private val dockOvershootPx = resources.getDimension(R.dimen.taipo_floating_dock_overshoot)

    /** Barres système et encoche (px), relues à chaque changement : la carte reste en dehors. */
    private val systemInsets = Rect()
    private var dragStart: FloatingPosition? = null

    /** Vrai pendant un glissement qui recollera le clavier au lever du doigt : la carte s'estompe pour le signaler. */
    private var dockArmed = false
        set(value) {
            if (field == value) return
            field = value
            card.alpha = if (value) DOCK_ARMED_ALPHA else 1f
            if (value) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }

    init {
        clipChildren = false
        clipToPadding = false
        handle.visibility = View.GONE
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemInsets.set(bars.left, bars.top, bars.right, bars.bottom)
            if (floating) requestLayout()
            insets
        }
    }

    /** Vrai si la racine occupe réellement tout l'écran (fenêtre agrandie) : sinon, on reste en disposition ancrée. */
    private fun isFloatingLayout(heightMeasureSpec: Int): Boolean =
        floating && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (!isFloatingLayout(heightMeasureSpec)) {
            measureChild(card, widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(width, card.measuredHeight)
            return
        }
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val area = area(width, height)
        val cardWidth = (area.width * widthPercent / PERCENT).coerceAtLeast(0)
        card.measure(
            MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(area.height, MeasureSpec.AT_MOST),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        if (!floating || height <= card.measuredHeight) {
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            return
        }
        val offset = FloatingPlacement.place(position, card.measuredWidth, card.measuredHeight, area(width, height))
        card.layout(offset.left, offset.top, offset.left + card.measuredWidth, offset.top + card.measuredHeight)
    }

    private fun area(width: Int, height: Int) = FloatingArea(
        left = systemInsets.left + screenMarginPx,
        top = systemInsets.top + screenMarginPx,
        right = width - systemInsets.right - screenMarginPx,
        bottom = height - systemInsets.bottom - screenMarginPx,
    )

    /** Rectangle de la carte, en coordonnées de la fenêtre : la seule zone qui reçoit les touchers en flottant. */
    fun cardBoundsInWindow(out: Rect) {
        val location = IntArray(2)
        card.getLocationInWindow(location)
        out.set(location[0], location[1], location[0] + card.width, location[1] + card.height)
    }

    private fun onHandleDrag(dx: Float, dy: Float) {
        val start = dragStart ?: position.also { dragStart = it }
        val area = area(width, height)
        position = FloatingPlacement.dragged(start, dx, dy, card.width, card.height, area)
        dockArmed = FloatingPlacement.shouldDock(start, dy, card.width, card.height, area, dockOvershootPx)
    }

    private fun onHandleRelease(cancelled: Boolean) {
        val start = dragStart ?: return
        dragStart = null
        val dock = dockArmed && !cancelled
        dockArmed = false
        if (dock) {
            // Le clavier reviendra en flottant là où il était avant ce geste.
            position = start
            onDockRequested?.invoke()
        } else {
            onPositionChanged?.invoke(position)
        }
    }

    /** Petite barre horizontale centrée, sous les touches : on la fait glisser pour déplacer le clavier. */
    private inner class HandleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_hint) }
        private val bar = RectF()
        private val barWidth = resources.getDimension(R.dimen.taipo_floating_handle_bar_width)
        private val barHeight = resources.getDimension(R.dimen.taipo_floating_handle_bar_height)
        private val handleHeight = resources.getDimensionPixelSize(R.dimen.taipo_floating_handle_height)
        private var downX = 0f
        private var downY = 0f

        init {
            contentDescription = context.getString(R.string.floating_handle_description)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), handleHeight)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            bar.set(cx - barWidth / 2f, cy - barHeight / 2f, cx + barWidth / 2f, cy + barHeight / 2f)
            canvas.drawRoundRect(bar, barHeight / 2f, barHeight / 2f, paint)
        }

        // Coordonnées brutes (écran) : la poignée se déplace avec la carte pendant le geste.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragStart = position
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> onHandleDrag(event.rawX - downX, event.rawY - downY)
                MotionEvent.ACTION_UP -> onHandleRelease(cancelled = false)
                MotionEvent.ACTION_CANCEL -> onHandleRelease(cancelled = true)
            }
            return true
        }
    }

    private companion object {
        const val PERCENT = 100
        const val DOCK_ARMED_ALPHA = 0.6f
    }
}
