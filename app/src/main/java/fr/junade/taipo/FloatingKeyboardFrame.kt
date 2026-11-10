package fr.junade.taipo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlin.math.roundToInt

/**
 * Racine de la vue du clavier : porte la « carte » (barre du haut, touches, panneaux, zone de chat : tout le clavier).
 *
 * - Ancré (par défaut) : la carte prend toute la largeur, la racine prend sa hauteur. Rien ne change par rapport à avant.
 * - Flottant ([floating]) : la fenêtre du clavier couvre l'écran (voir [FloatingKeyboardWindow]), la racine aussi ; la carte,
 *   réduite à [widthPercent] de la largeur utile, est placée à [position] ([FloatingPlacement]), hors des barres système.
 *   Coins arrondis et ombre. Seule la carte reçoit les touchers.
 *
 * Toutes les transitions sont en ressort ([FloatingTransition]) : envol et atterrissage entre les deux formes (la fenêtre reste
 * plein écran tant que la carte se recolle, voir [needsFullWindow] et [onDocked]), carte soulevée tant que le doigt tient la
 * poignée, zone d'ancrage « Relâcher pour ancrer » quand la carte est poussée sous le bas de l'écran, apparition sur place à
 * l'ouverture d'un champ ([playAppear]).
 *
 * La poignée ([handle], sous les touches) déplace la carte ; la nouvelle position est confiée à [onPositionChanged] au lever du
 * doigt. Lâchée dans la zone d'ancrage, elle demande le recollage ([onDockRequested]). TalkBack : actions « monter »,
 * « descendre » et « recoller » sur la poignée.
 */
class FloatingKeyboardFrame(context: Context, private val card: View) : FrameLayout(context) {

    /** Poignée de déplacement : à placer dans la carte, sous les touches. */
    val handle: View = HandleView(context)

    private val dockZone = DockZoneView(context)
    private val transition = FloatingTransition { ValueAnimator.areAnimatorsEnabled() }

    /** Forme visée (la carte peut encore être en train d'y aller). */
    var floating = false
        private set

    /** Vrai tant que la fenêtre doit couvrir l'écran : clavier flottant, ou carte en train de se recoller. */
    val needsFullWindow: Boolean get() = floating || transition.isMorphing

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

    /** La poignée a été lâchée dans la zone d'ancrage, ou TalkBack a demandé le recollage : le clavier se recolle. */
    var onDockRequested: (() -> Unit)? = null

    /** Avancement de la forme à chaque image (0 = ancré, 1 = flottant) : la marge du bas des touches le suit. */
    var onMorphProgress: ((Float) -> Unit)? = null

    /** La carte vient de finir de se recoller : la fenêtre peut reprendre la hauteur du clavier. */
    var onDocked: (() -> Unit)? = null

    private val cornerRadiusPx = resources.getDimension(R.dimen.taipo_floating_corner_radius)
    private val elevationPx = resources.getDimension(R.dimen.taipo_floating_elevation)
    private val screenMarginPx = resources.getDimensionPixelSize(R.dimen.taipo_floating_screen_margin)
    private val dockOvershootPx = resources.getDimension(R.dimen.taipo_floating_dock_overshoot)
    private val dockZoneHeightPx = resources.getDimensionPixelSize(R.dimen.taipo_floating_dock_zone_height)

    /** Barres système et encoche (px), relues à chaque changement : la carte reste en dehors. */
    private val systemInsets = Rect()
    private var dragStart: FloatingPosition? = null
    private var dockArmed = false
        set(value) {
            if (field == value) return
            field = value
            transition.setArmed(value)
            if (value) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            schedule()
        }

    private var lastFrameNanos = 0L
    private var frameScheduled = false
    private val frameRunnable = Runnable { onAnimationFrame() }

    init {
        clipChildren = false
        clipToPadding = false
        card.outlineProvider = ViewOutlineProvider.BACKGROUND
        addView(dockZone, LayoutParams(LayoutParams.MATCH_PARENT, dockZoneHeightPx))
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemInsets.set(bars.left, bars.top, bars.right, bars.bottom)
            if (needsFullWindow) requestLayout()
            insets
        }
        setUpHandleAccessibility()
        applyPose()
    }

    /**
     * Change la forme du clavier. [animate] : geste de l'utilisateur (bulle d'Entrée, poignée), avec envol ou atterrissage ;
     * sinon (ouverture d'un champ, réglage changé ailleurs) la forme est posée directement.
     */
    fun setFloating(value: Boolean, animate: Boolean) {
        if (floating == value) return
        floating = value
        transition.setFloating(value, animate && isAttachedToWindow && isShown)
        if (animate) announceMode(value)
        requestLayout()
        applyPose()
        schedule()
    }

    /** Ouverture d'un champ en mode flottant : la carte apparaît là où elle a été laissée. */
    fun playAppear() {
        if (!floating) return
        transition.playAppear()
        applyPose()
        schedule()
    }

    /** Rectangle de la carte, en coordonnées de la fenêtre : la seule zone qui reçoit les touchers en flottant. */
    fun cardBoundsInWindow(out: Rect) {
        val location = IntArray(2)
        card.getLocationInWindow(location)
        out.set(location[0], location[1], location[0] + card.width, location[1] + card.height)
    }

    /** Vrai si la racine occupe réellement tout l'écran (fenêtre agrandie) : sinon, on reste en disposition ancrée. */
    private fun isFullWindowLayout(heightMeasureSpec: Int): Boolean =
        needsFullWindow && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (!isFullWindowLayout(heightMeasureSpec)) {
            measureChild(card, widthMeasureSpec, heightMeasureSpec)
            dockZone.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
            setMeasuredDimension(width, card.measuredHeight)
            return
        }
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val area = area(width, height)
        val floatingWidth = area.width * widthPercent / PERCENT
        val cardWidth = lerp(width.toFloat(), floatingWidth.toFloat(), transition.morphProgress).roundToInt().coerceIn(0, width)
        card.measure(
            MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        dockZone.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dockZoneHeightPx + systemInsets.bottom, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        if (!needsFullWindow || height <= card.measuredHeight) {
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            dockZone.layout(0, height, width, height)
            return
        }
        val cardWidth = card.measuredWidth
        val cardHeight = card.measuredHeight
        val floatingOffset = FloatingPlacement.place(position, cardWidth, cardHeight, area(width, height))
        val p = transition.morphProgress
        val cardLeft = lerp(0f, floatingOffset.left.toFloat(), p).roundToInt()
        val cardTop = lerp((height - cardHeight).toFloat(), floatingOffset.top.toFloat(), p).roundToInt()
        card.layout(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)
        dockZone.layout(0, height - dockZone.measuredHeight, width, height)
    }

    private fun area(width: Int, height: Int) = FloatingArea(
        left = systemInsets.left + screenMarginPx,
        top = systemInsets.top + screenMarginPx,
        right = width - systemInsets.right - screenMarginPx,
        bottom = height - systemInsets.bottom - screenMarginPx,
    )

    private fun schedule() {
        if (frameScheduled || !transition.isRunning) return
        frameScheduled = true
        lastFrameNanos = 0L
        postOnAnimation(frameRunnable)
    }

    private fun onAnimationFrame() {
        frameScheduled = false
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) FIRST_FRAME_S else ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastFrameNanos = now
        val wasMorphing = transition.isMorphing
        transition.step(dt)
        if (wasMorphing) requestLayout()
        applyPose()
        if (wasMorphing && !transition.isMorphing && !floating) onDocked?.invoke()
        if (transition.isRunning) {
            frameScheduled = true
            postOnAnimation(frameRunnable)
        }
    }

    /** Pose de la carte à l'image courante : coins, ombre, poignée, soulèvement, apparition, zone d'ancrage. */
    private fun applyPose() {
        val fraction = transition.morphFraction
        (card.background as? KeyboardBackgroundDrawable)?.cornerRadius = cornerRadiusPx * transition.morphProgress.coerceAtLeast(0f)
        card.elevation = elevationPx * fraction * transition.liftShadow
        val scale = transition.liftScale * transition.appearScale
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f
        card.scaleX = scale
        card.scaleY = scale
        card.alpha = transition.appearAlpha
        (handle as HandleView).reveal = fraction
        dockZone.alpha = transition.dockZoneAlpha
        dockZone.visibility = if (transition.dockZoneAlpha > 0f) View.VISIBLE else View.INVISIBLE
        onMorphProgress?.invoke(transition.morphProgress)
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
        transition.setLifted(false)
        val dock = dockArmed && !cancelled
        dockArmed = false
        schedule()
        if (dock) {
            // La position retenue reste celle d'avant ce geste : le clavier y reviendra en flottant. La carte atterrit d'ici.
            onDockRequested?.invoke()
        } else if (position != start) {
            onPositionChanged?.invoke(position)
        }
    }

    /** TalkBack : la poignée se déplace par actions (un cinquième de l'écran à la fois) et peut recoller le clavier. */
    private fun setUpHandleAccessibility() {
        fun moveBy(fraction: Float) {
            position = FloatingPosition.of(position.x, position.y + fraction)
            onPositionChanged?.invoke(position)
        }
        ViewCompat.replaceAccessibilityAction(
            handle,
            AccessibilityActionCompat(R.id.floating_action_move_up, context.getString(R.string.floating_a11y_move_up)),
            null,
        ) { _, _ ->
            moveBy(-ACCESSIBILITY_STEP)
            true
        }
        ViewCompat.replaceAccessibilityAction(
            handle,
            AccessibilityActionCompat(R.id.floating_action_move_down, context.getString(R.string.floating_a11y_move_down)),
            null,
        ) { _, _ ->
            moveBy(ACCESSIBILITY_STEP)
            true
        }
        ViewCompat.replaceAccessibilityAction(
            handle,
            AccessibilityActionCompat(R.id.floating_action_dock, context.getString(R.string.floating_a11y_dock)),
            null,
        ) { _, _ ->
            onDockRequested?.invoke()
            true
        }
    }

    @Suppress("DEPRECATION") // announceForAccessibility : seul moyen simple d'annoncer un changement de mode depuis un clavier.
    private fun announceMode(floating: Boolean) {
        announceForAccessibility(context.getString(if (floating) R.string.floating_a11y_floating else R.string.floating_a11y_docked))
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

        /** 0 = masquée (clavier ancré), 1 = pleine hauteur (flottant) : la poignée suit l'envol et l'atterrissage. */
        var reveal = 0f
            set(value) {
                if (field == value) return
                val wasShown = field > 0f
                field = value
                visibility = if (value > 0f) View.VISIBLE else View.GONE
                if (wasShown != (value > 0f) || value < 1f) requestLayout()
                invalidate()
            }

        init {
            contentDescription = context.getString(R.string.floating_handle_description)
            visibility = View.GONE
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (handleHeight * reveal).roundToInt())
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
            if (!floating) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragStart = position
                    transition.setLifted(true)
                    schedule()
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> onHandleDrag(event.rawX - downX, event.rawY - downY)
                MotionEvent.ACTION_UP -> onHandleRelease(cancelled = false)
                MotionEvent.ACTION_CANCEL -> onHandleRelease(cancelled = true)
            }
            return true
        }
    }

    /** Zone d'ancrage au bas de l'écran : dégradé violet et « Relâcher pour ancrer », au-dessus de la barre de navigation. */
    private inner class DockZoneView(context: Context) : View(context) {
        private val accent = context.themeColor(R.color.accent)
        private val fill = Paint()
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textAlign = Paint.Align.CENTER
            textSize = resources.getDimension(R.dimen.taipo_settings_text_caption)
            isFakeBoldText = true
        }
        private val label = context.getString(R.string.floating_dock_zone)

        init {
            visibility = View.INVISIBLE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            fill.shader = LinearGradient(
                0f,
                h.toFloat(),
                0f,
                0f,
                ColorUtils.setAlphaComponent(accent, DOCK_ZONE_ALPHA),
                ColorUtils.setAlphaComponent(accent, 0),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            val baseline = height - systemInsets.bottom - text.textSize
            canvas.drawText(label, width / 2f, baseline, text)
        }
    }

    private companion object {
        const val PERCENT = 100
        const val ACCESSIBILITY_STEP = 0.2f
        const val DOCK_ZONE_ALPHA = 110
        const val FIRST_FRAME_S = 1f / 60f
        const val MAX_FRAME_S = 0.033f
        const val NANOS_PER_SECOND = 1_000_000_000f

        fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
    }
}
