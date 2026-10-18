package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.random.Random

/**
 * Animation « Bond » de l'icône Maj. Transitions (la face de la touche ne change pas) :
 * - minuscule → majuscule : la flèche s'écrase, bondit en s'étirant et se remplit de bas en haut ; si la majuscule s'active
 *   toute seule (début de phrase), un second bond et un anneau qui se propage remplacent la confirmation du doigt ;
 * - majuscule → minuscule : la flèche s'étire, retombe et se vide ;
 * - verrouillage : une barre tombe sous la flèche avec un rebond, la flèche se raccourcit, deux étincelles de chaque côté ;
 * - déverrouillage : la barre s'efface vers le bas.
 *
 * Même principe que [BackspaceAnimation] : logique pure (testée en JVM), la vue appelle [advance] avant le dessin puis
 * dessine l'icône avec [ShiftIconPainter] ; [sparks] et l'anneau ([ShiftRingPainter]) sont dessinés par-dessus les touches.
 * Animations système coupées ([animationsEnabled] faux) : l'icône prend directement sa pose de repos.
 */
internal class ShiftAnimation(
    private val density: Float,
    private val animationsEnabled: () -> Boolean,
    random: Random = Random.Default,
) {

    val sparks = SparkField(density, random)

    var state = ShiftState.OFF
        private set

    private val sx = TunedSpring(1f)
    private val sy = TunedSpring(1f)
    private val lift = TunedSpring(0f)
    private val fillSpring = TunedSpring(0f)
    private val barOpacitySpring = TunedSpring(0f)
    private val barScaleSpring = TunedSpring(ShiftIconPose.BAR_HIDDEN_SCALE)
    private val barOffsetSpring = TunedSpring(0f)

    val scaleX: Float get() = sx.value
    val scaleY: Float get() = sy.value

    /** Décalage vertical de la flèche (grille de 24, négatif vers le haut). */
    val arrowLift: Float get() = lift.value
    val fill: Float get() = fillSpring.value
    val barOpacity: Float get() = barOpacitySpring.value
    val barScale: Float get() = barScaleSpring.value
    val barOffset: Float get() = barOffsetSpring.value

    // Centre de l'icône dans la vue et pixels par unité de la grille, mémorisés à chaque transition (étincelles, anneau).
    private var centerX = 0f
    private var centerY = 0f
    private var unit = 1f
    private var lastNanos = 0L
    private var lockInS = NONE
    private var secondHopS = NONE
    private var ringAge = NONE

    val ringActive: Boolean get() = ringAge >= 0f

    /** Avancement de l'anneau de 0 à 1. */
    val ringProgress: Float get() = (ringAge / RING_LIFE_S).coerceIn(0f, 1f)
    val ringX: Float get() = centerX
    val ringY: Float get() = centerY + (RING_CENTER_Y - ICON_CENTER) * unit

    private val springs = arrayOf(sx, sy, lift, fillSpring, barOpacitySpring, barScaleSpring, barOffsetSpring)

    val isRunning: Boolean
        get() = lockInS >= 0f || secondHopS >= 0f || ringAge >= 0f || sparks.aliveCount > 0 || hasMovingSpring()

    /** Pose directe (premier dessin, vue détachée, animations coupées). */
    fun snap(to: ShiftState) {
        state = to
        sx.jump(1f)
        sy.jump(ShiftIconPose.restScaleY(to))
        lift.jump(ShiftIconPose.restLift(to))
        fillSpring.jump(ShiftIconPose.restFill(to))
        barOpacitySpring.jump(ShiftIconPose.restBarOpacity(to))
        barScaleSpring.jump(ShiftIconPose.restBarScale(to))
        barOffsetSpring.jump(0f)
        lockInS = NONE
        secondHopS = NONE
        ringAge = NONE
        sparks.clear()
        lastNanos = 0L
    }

    /**
     * L'état de Maj vient de changer. (cx, cy) : centre de l'icône dans la vue, [iconSizePx] : côté de l'icône ; [auto] :
     * la majuscule s'est activée toute seule (aucun appui sur la touche).
     */
    fun onState(to: ShiftState, auto: Boolean, cx: Float, cy: Float, iconSizePx: Float) {
        val from = state
        if (from == to) return
        if (!animationsEnabled()) {
            snap(to)
            return
        }
        state = to
        centerX = cx
        centerY = cy
        unit = iconSizePx / ShiftIconPose.GRID
        lockInS = NONE
        when (to) {
            ShiftState.ON -> if (from == ShiftState.OFF) activate(auto) else unlock()
            ShiftState.LOCK -> if (from == ShiftState.OFF) {
                activate(auto)
                lockInS = LOCK_AFTER_ACTIVATE_S
            } else {
                lockIn()
            }
            ShiftState.OFF -> deactivate(from)
        }
    }

    /** Avance l'animation jusqu'à [nowNanos] (une fois par image, avant le dessin). */
    fun advance(nowNanos: Long) {
        val dt = if (lastNanos == 0L) 0f else ((nowNanos - lastNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastNanos = nowNanos
        step(dt)
        if (!isRunning) lastNanos = 0L
    }

    /** Avance de [dt] secondes. */
    fun step(dt: Float) {
        if (lockInS >= 0f) {
            lockInS -= dt
            if (lockInS < 0f) lockIn()
        }
        if (secondHopS >= 0f) {
            secondHopS -= dt
            if (secondHopS < 0f) hop(SECOND_HOP_SPEED)
        }
        if (ringAge >= 0f) {
            ringAge += dt
            if (ringAge >= RING_LIFE_S) ringAge = NONE
        }
        for (spring in springs) spring.step(dt)
        sparks.step(dt)
    }

    /** Coupe tout et revient à la pose de repos de l'état courant. */
    fun cancel() = snap(state)

    private fun activate(auto: Boolean) {
        sx.jump(CROUCH_SCALE_X)
        sy.jump(CROUCH_SCALE_Y)
        sx.go(1f, 240f, 9f)
        sy.go(1f, 240f, 9f)
        lift.go(0f, 200f, 8f, -FIRST_HOP_SPEED)
        fillSpring.go(1f, 150f, 15f)
        if (auto) {
            ringAge = 0f
            secondHopS = SECOND_HOP_AFTER_S
        }
    }

    private fun hop(speed: Float) {
        sy.jump(SECOND_CROUCH_SCALE_Y)
        sy.go(1f, 240f, 9f)
        lift.go(0f, 210f, 8f, -speed)
    }

    private fun lockIn() {
        barOpacitySpring.jump(1f)
        barScaleSpring.jump(LAND_BAR_SCALE)
        barOffsetSpring.jump(-BAR_DROP)
        sy.jump(LAND_SCALE_Y)
        barOffsetSpring.go(0f, 260f, 11f)
        barScaleSpring.go(1f, 260f, 11f)
        sy.go(ShiftIconPose.LOCK_SCALE_Y, 240f, 9f)
        lift.go(ShiftIconPose.LOCK_LIFT, 240f, 9f)
        fillSpring.go(1f, 150f, 15f)
        val left = gridX(ShiftIconPose.BAR_LEFT_X - BAR_SPARK_INSET)
        val right = gridX(ShiftIconPose.BAR_RIGHT_X + BAR_SPARK_INSET)
        val y = gridY(ShiftIconPose.BAR_Y)
        sparks.burst(left, y, BAR_SPARKS, BAR_SPARK_RADIUS_DP, BAR_SPARK_LIFE_S)
        sparks.burst(right, y, BAR_SPARKS, BAR_SPARK_RADIUS_DP, BAR_SPARK_LIFE_S)
    }

    /** Verrouillé → majuscule simple : la barre part, la flèche reprend sa hauteur avec un petit rebond. */
    private fun unlock() {
        dropBar()
        sy.jump(UNLOCK_SCALE_Y)
        sy.go(1f, 240f, 9f)
        lift.go(0f, 220f, 9f, -UNLOCK_HOP_SPEED)
    }

    private fun deactivate(from: ShiftState) {
        secondHopS = NONE
        sx.jump(STRETCH_SCALE_X)
        sy.jump(STRETCH_SCALE_Y)
        sx.go(1f, 240f, 10f)
        sy.go(1f, 240f, 10f)
        lift.go(0f, 220f, 9f, DROP_SPEED)
        fillSpring.go(0f, 170f, 22f)
        if (from == ShiftState.LOCK) dropBar()
    }

    private fun dropBar() {
        barOffsetSpring.go(BAR_FALL, 200f, 22f)
        barOpacitySpring.go(0f, 200f, 26f)
        barScaleSpring.go(ShiftIconPose.BAR_HIDDEN_SCALE, 200f, 22f)
    }

    private fun hasMovingSpring(): Boolean {
        for (spring in springs) if (!spring.isSettled) return true
        return false
    }

    private fun gridX(gx: Float): Float = centerX + (gx - ICON_CENTER) * unit

    private fun gridY(gy: Float): Float = centerY + (gy - ICON_CENTER) * unit

    private companion object {
        const val NONE = -1f
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
        const val ICON_CENTER = 12f
        const val RING_CENTER_Y = 11.25f
        const val RING_LIFE_S = 0.52f
        const val LOCK_AFTER_ACTIVATE_S = 0.23f
        const val SECOND_HOP_AFTER_S = 0.38f
        const val CROUCH_SCALE_X = 1.18f
        const val CROUCH_SCALE_Y = 0.68f
        const val SECOND_CROUCH_SCALE_Y = 0.8f
        const val FIRST_HOP_SPEED = 75f
        const val SECOND_HOP_SPEED = 60f
        const val LAND_SCALE_Y = 0.8f
        const val LAND_BAR_SCALE = 0.3f
        const val BAR_DROP = 10f
        const val BAR_FALL = 8f
        const val UNLOCK_SCALE_Y = 0.9f
        const val UNLOCK_HOP_SPEED = 40f
        const val STRETCH_SCALE_X = 0.86f
        const val STRETCH_SCALE_Y = 1.22f
        const val DROP_SPEED = 70f
        const val BAR_SPARKS = 2
        const val BAR_SPARK_INSET = 1f
        const val BAR_SPARK_RADIUS_DP = 16f
        const val BAR_SPARK_LIFE_S = 0.52f
    }
}

/** Dessin de l'icône Maj (flèche creuse ou pleine, barre) à la pose de [ShiftAnimation], teintée de la couleur des libellés de touche. */
internal class ShiftIconPainter(color: Int) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    // Tracé de l'ancienne icône Maj (ic_key_shift), grille de 24.
    private val arrow = Path().apply {
        moveTo(12f, 3.5f)
        lineTo(4.5f, 12f)
        lineTo(9f, 12f)
        lineTo(9f, 19f)
        lineTo(15f, 19f)
        lineTo(15f, 12f)
        lineTo(19.5f, 12f)
        close()
    }

    /** Dessine l'icône centrée en (cx, cy), de côté [sizePx]. */
    fun draw(canvas: Canvas, animation: ShiftAnimation, cx: Float, cy: Float, sizePx: Float) {
        val unit = sizePx / ShiftIconPose.GRID
        canvas.save()
        canvas.translate(cx - sizePx / 2f, cy - sizePx / 2f)
        canvas.scale(unit, unit)

        canvas.save()
        canvas.translate(ARROW_CENTER_X, ShiftIconPose.PIVOT_Y + animation.arrowLift)
        canvas.scale(animation.scaleX, animation.scaleY)
        canvas.translate(-ARROW_CENTER_X, -ShiftIconPose.PIVOT_Y)
        val fill = animation.fill.coerceIn(0f, 1f)
        if (fill > 0f) {
            // Le remplissage monte de bas en haut dans la flèche.
            canvas.save()
            canvas.clipRect(-CLIP_MARGIN, ShiftIconPose.GRID * (1f - fill), ShiftIconPose.GRID + CLIP_MARGIN, ShiftIconPose.GRID + CLIP_MARGIN)
            canvas.drawPath(arrow, fillPaint)
            canvas.restore()
        }
        canvas.drawPath(arrow, strokePaint)
        canvas.restore()

        val barOpacity = animation.barOpacity.coerceIn(0f, 1f)
        val barScale = animation.barScale.coerceAtLeast(0f)
        if (barOpacity > MIN_VISIBLE && barScale > 0f) {
            strokePaint.alpha = (ALPHA_FULL * barOpacity).toInt()
            canvas.save()
            canvas.translate(ARROW_CENTER_X, ShiftIconPose.BAR_Y + animation.barOffset)
            canvas.scale(barScale, 1f)
            canvas.translate(-ARROW_CENTER_X, -ShiftIconPose.BAR_Y)
            canvas.drawLine(ShiftIconPose.BAR_LEFT_X, ShiftIconPose.BAR_Y, ShiftIconPose.BAR_RIGHT_X, ShiftIconPose.BAR_Y, strokePaint)
            canvas.restore()
            strokePaint.alpha = ALPHA_FULL.toInt()
        }
        canvas.restore()
    }

    private companion object {
        const val STROKE_WIDTH = 2f
        const val ARROW_CENTER_X = 12f
        const val CLIP_MARGIN = 6f
        const val MIN_VISIBLE = 0.01f
        const val ALPHA_FULL = 255f
    }
}

/** Anneau qui se propage autour de l'icône quand la majuscule s'active toute seule (violet clair, de 0,5 à 1,9 fois 18 dp de rayon). */
internal class ShiftRingPainter(color: Int, private val density: Float) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        this.color = color
    }

    fun draw(canvas: Canvas, animation: ShiftAnimation) {
        if (!animation.ringActive) return
        val p = animation.ringProgress
        paint.alpha = (ALPHA_FULL * START_ALPHA * (1f - p)).toInt()
        canvas.drawCircle(animation.ringX, animation.ringY, RADIUS_DP * density * (START_SCALE + SCALE_GAIN * BackspaceSparkPose.ease(p)), paint)
    }

    private companion object {
        const val RADIUS_DP = 18f
        const val STROKE_DP = 2f
        const val START_SCALE = 0.5f
        const val SCALE_GAIN = 1.4f
        const val START_ALPHA = 0.8f
        const val ALPHA_FULL = 255f
    }
}
