package fr.junade.taipo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Animation « Amibe » du trait de la barre espace pendant le glissement qui déplace le curseur : deux ressorts sur le même
 * axe horizontal. Le bout avant (dans le sens où le trait doit aller) est raide et colle au doigt ; le bout arrière est mou
 * et traîne, donc le trait s'étire. Au lâcher, les deux bouts reviennent au centre avec un petit rebond.
 *
 * Même principe que [BackspaceAnimation] et [ShiftAnimation] : logique pure (testée en JVM), la vue appelle [onDrag] et
 * [onRelease] avec les gestes, puis [advance] une fois par image avant de dessiner ; [KeyRenderer] lit [rightOffset] et
 * [leftOffset] (décalages en pixels par rapport aux deux extrémités du trait au repos) et en déduit la forme avec
 * [SpaceBarPose]. Animations système coupées ([animationsEnabled] faux) : le trait reste immobile au centre.
 */
internal class SpaceBarAnimation(
    private val density: Float,
    private val animationsEnabled: () -> Boolean,
    /** Demi-longueur du trait au repos, en pixels (relue à chaque image : elle suit les dimensions de la vue). */
    private val halfLengthPx: () -> Float,
) {

    private val right = Spring()
    private val left = Spring()

    /** Décalage de l'extrémité droite du trait par rapport à sa position de repos, en pixels. */
    val rightOffset: Float get() = right.x

    /** Décalage de l'extrémité gauche du trait par rapport à sa position de repos, en pixels. */
    val leftOffset: Float get() = left.x

    /** Vrai si le bout avant est le bout droit (le trait va vers la droite). */
    var headIsRight = true
        private set

    private var dragging = false
    private var target = 0f
    private var lastTarget = 0f
    private var targetSpeed = 0f
    private var lastNanos = 0L

    val isRunning: Boolean
        get() = dragging || !right.isSettled() || !left.isSettled()

    /**
     * Le glissement se poursuit : le doigt est à [offsetPx] de son point d'appui (négatif à gauche), et le trait peut
     * s'éloigner du centre de [maxTravelPx] au plus (butée douce : voir [SpaceBarPose.limitedTravel]).
     */
    fun onDrag(offsetPx: Float, maxTravelPx: Float) {
        if (!animationsEnabled()) return
        dragging = true
        target = SpaceBarPose.limitedTravel(offsetPx, maxTravelPx)
    }

    /** Doigt levé (ou geste annulé) : le trait revient au centre. */
    fun onRelease() {
        dragging = false
        target = 0f
    }

    /** Remet le trait au repos immédiatement (vue détachée, animations coupées). */
    fun cancel() {
        dragging = false
        target = 0f
        lastTarget = 0f
        targetSpeed = 0f
        lastNanos = 0L
        right.snap()
        left.snap()
    }

    fun advance(nowNanos: Long) {
        val dt = if (lastNanos == 0L) 0f else ((nowNanos - lastNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastNanos = nowNanos
        step(dt)
        if (!isRunning) lastNanos = 0L
    }

    /** Avance de [dt] secondes. */
    fun step(dt: Float) {
        if (dt <= 0f) return
        if (!animationsEnabled()) {
            cancel()
            return
        }
        // Vitesse de la cible, lissée : le bout avant s'étire un peu plus loin quand le doigt va vite.
        val rawSpeed = (target - lastTarget) / dt
        lastTarget = target
        targetSpeed += (rawSpeed - targetSpeed) * SPEED_SMOOTHING

        val mid = (right.x + left.x) / 2f
        if (abs(target - mid) > DIRECTION_DEADBAND_DP * density) headIsRight = target > mid
        val stretchAhead = minOf(abs(targetSpeed) / (SPEED_FULL_DP_S * density), 1f) * STRETCH_AHEAD_DP * density
        val rightTarget = target + if (headIsRight) stretchAhead else 0f
        val leftTarget = target - if (headIsRight) 0f else stretchAhead

        val n = ceil(dt / SUBSTEP_S).toInt().coerceAtLeast(1)
        val h = dt / n
        val maxShrink = 2f * halfLengthPx() - MIN_LENGTH_DP * density
        repeat(n) {
            if (headIsRight) {
                right.step(rightTarget, HEAD_STIFFNESS, HEAD_DAMPING, h)
                left.step(leftTarget, TAIL_STIFFNESS, TAIL_DAMPING, h)
            } else {
                left.step(leftTarget, HEAD_STIFFNESS, HEAD_DAMPING, h)
                right.step(rightTarget, TAIL_STIFFNESS, TAIL_DAMPING, h)
            }
            // Les deux bouts ne se croisent jamais : le trait ne descend pas sous sa longueur minimale.
            val deficit = -((right.x - left.x) + maxShrink)
            if (deficit > 0f) {
                right.x += deficit / 2f
                left.x -= deficit / 2f
            }
        }
    }

    /** Ressort amorti à masse 1 (raideur et ratio d'amortissement comme un SpringForce Android), intégré en Euler semi-implicite. */
    private class Spring {
        var x = 0f
        private var v = 0f

        fun step(target: Float, stiffness: Float, dampingRatio: Float, dt: Float) {
            v += (stiffness * (target - x) - 2f * dampingRatio * sqrt(stiffness) * v) * dt
            x += v * dt
        }

        fun isSettled() = abs(x) < SETTLED_PX && abs(v) < SETTLED_PX_PER_S

        fun snap() {
            x = 0f
            v = 0f
        }
    }

    companion object {
        const val HEAD_STIFFNESS = 900f
        const val HEAD_DAMPING = 0.6f
        const val TAIL_STIFFNESS = 140f
        const val TAIL_DAMPING = 0.45f

        /** Le bout avant dépasse la cible d'au plus cette distance quand le doigt va vite. */
        const val STRETCH_AHEAD_DP = 8f

        /** Vitesse de la cible à partir de laquelle l'anticipation est maximale. */
        const val SPEED_FULL_DP_S = 900f

        /** Longueur minimale du trait, pour que le bout arrière ne rattrape jamais le bout avant. */
        const val MIN_LENGTH_DP = 12f

        private const val DIRECTION_DEADBAND_DP = 0.6f
        private const val SPEED_SMOOTHING = 0.25f
        private const val SUBSTEP_S = 1f / 240f
        private const val MAX_FRAME_S = 0.033f
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val SETTLED_PX = 0.05f
        private const val SETTLED_PX_PER_S = 1f
    }
}

/**
 * Forme du trait de la barre espace pendant l'animation « Amibe » : calculs purs, sans Android. Le trait est une capsule
 * entre deux cercles ; étiré, le bout avant gonfle et le bout arrière s'amincit ; raccourci, les deux s'épaississent.
 */
internal object SpaceBarPose {

    /** Allongement relatif du trait : 0 au repos, positif étiré, négatif raccourci (de [lengthPx] contre [restLengthPx]). */
    fun stretch(lengthPx: Float, restLengthPx: Float): Float = (lengthPx - restLengthPx) / restLengthPx

    /** Rayon du bout avant pour un allongement [stretch] (rayon de repos [restRadius]). */
    fun headRadius(stretch: Float, restRadius: Float): Float =
        if (stretch >= 0f) {
            restRadius * (1f + HEAD_GROWTH * stretch.coerceAtMost(MAX_STRETCH))
        } else {
            restRadius * (1f - SHRINK_GROWTH * stretch.coerceAtLeast(MIN_STRETCH))
        }

    /** Rayon du bout arrière pour un allongement [stretch]. */
    fun tailRadius(stretch: Float, restRadius: Float): Float =
        if (stretch >= 0f) {
            (restRadius * (1f - TAIL_THINNING * stretch.coerceAtMost(MAX_STRETCH))).coerceAtLeast(restRadius * MIN_TAIL_RATIO)
        } else {
            headRadius(stretch, restRadius)
        }

    /** Part de teinte violette (0 à [MAX_TINT]) : le trait s'éclaire quand il s'étire ou se comprime. */
    fun tint(stretch: Float): Float = (abs(stretch) * TINT_PER_STRETCH).coerceIn(0f, MAX_TINT)

    /** Distance du trait au centre pour un doigt à [offsetPx] de son point d'appui : proportionnelle au début, puis butée douce à [maxTravelPx]. */
    fun limitedTravel(offsetPx: Float, maxTravelPx: Float): Float {
        if (maxTravelPx <= 0f) return 0f
        return maxTravelPx * tanh(offsetPx / (maxTravelPx * TRAVEL_SOFTNESS))
    }

    /** Mélange deux couleurs ARGB ; [fraction] 0 donne [from], 1 donne [to]. */
    fun blend(from: Int, to: Int, fraction: Float): Int {
        val t = fraction.coerceIn(0f, 1f)
        var result = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            val mixed = (a + (b - a) * t + 0.5f).toInt().coerceIn(0, 255)
            result = result or (mixed shl shift)
        }
        return result
    }

    const val HEAD_GROWTH = 0.55f
    const val TAIL_THINNING = 0.45f
    const val SHRINK_GROWTH = 0.6f
    const val MIN_TAIL_RATIO = 0.4f
    const val MAX_STRETCH = 1.6f
    const val MIN_STRETCH = -0.5f
    const val TINT_PER_STRETCH = 0.6f
    const val MAX_TINT = 0.6f
    private const val TRAVEL_SOFTNESS = 1.1f
}
