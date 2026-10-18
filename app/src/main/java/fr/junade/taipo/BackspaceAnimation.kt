package fr.junade.taipo

import kotlin.math.abs
import kotlin.random.Random

/**
 * État de l'animation de l'icône retour arrière du clavier : balancier à chaque suppression, inclinaison pendant le
 * glissement vers la gauche, et étincelles (une gerbe à chaque suppression, une traînée sous le doigt pendant le glissement,
 * une gerbe au relâchement). Le texte et le curseur appartiennent à l'application hôte : rien n'est animé dessus, tout se
 * passe dans le clavier. La touche elle-même n'est pas animée.
 *
 * Aucune dépendance à Android pour la logique (testée en JVM) : [KeyboardView] appelle [advance] une fois par image avant de
 * dessiner, lit [iconAngle] pour [KeyRenderer], et dessine [sparks] avec [SparkPainter]. Les étincelles vivent dans un
 * tampon de taille fixe (aucune allocation au dessin) ; si les animations système sont coupées
 * ([animationsEnabled] faux), rien ne se déclenche.
 */
internal class BackspaceAnimation(
    private val density: Float,
    private val animationsEnabled: () -> Boolean,
    random: Random = Random.Default,
) {

    /** Étincelles en cours (gerbes et traînée), dessinées par la vue. */
    val sparks = SparkField(density, random)

    /** Angle courant de l'icône en degrés (balancier + inclinaison), mis à jour par [advance]. */
    var iconAngle = 0f
        private set

    val isRunning: Boolean
        get() = swingTime >= 0f || sparks.aliveCount > 0 || !tilt.isSettledAngle(tiltTarget)

    private var swingTime = -1f
    private var swingAmplitude = 0f
    private val tilt = SpringValue()
    private var tiltTarget = 0f
    private var lastNanos = 0L
    private var lastBurstNanos = 0L
    private var lastTrailX = 0f
    private var lastWords = 0

    /** Une touche retour arrière vient d'être saisie ; (x, y) = centre de l'icône dans la vue. [repeat] : répétition d'un appui maintenu. */
    fun onKey(x: Float, y: Float, repeat: Boolean, nowNanos: Long) {
        if (!animationsEnabled()) return
        if (repeat) {
            if (swingTime < 0f || swingTime > REPEAT_RESTART_S) startSwing(BackspaceIconPose.SWING_REPEAT_DEGREES)
            if (lastBurstNanos != 0L && nowNanos - lastBurstNanos < REPEAT_BURST_GAP_NANOS) return
        } else {
            startSwing(BackspaceIconPose.SWING_TAP_DEGREES)
        }
        lastBurstNanos = nowNanos
        sparks.burst(x, y, TAP_SPARKS, TAP_RADIUS_DP, TAP_LIFE_S)
    }

    /** Doigt posé sur la touche : un nouveau geste commence. */
    fun onPress(x: Float) {
        lastTrailX = x
        lastWords = 0
        tiltTarget = 0f
    }

    /**
     * Le glissement vers la gauche se poursuit : le doigt est en (x, y), à [distancePx] de son point d'appui, et [words] mots
     * sont désormais surlignés dans l'application.
     */
    fun onDrag(x: Float, y: Float, distancePx: Float, words: Int) {
        if (!animationsEnabled()) return
        tiltTarget = BackspaceIconPose.dragTilt(distancePx / density)
        if (abs(x - lastTrailX) >= TRAIL_STEP_DP * density) {
            lastTrailX = x
            repeat(TRAIL_SPARKS) {
                sparks.spawn(
                    x, y + sparks.signed(TRAIL_SPREAD_DP) * density,
                    sparks.between(TRAIL_DRIFT_MIN_DP, TRAIL_DRIFT_MAX_DP) * density, sparks.signed(TRAIL_FALL_DP) * density,
                    TRAIL_LIFE_S,
                )
            }
        }
        if (words > lastWords) {
            startSwing(BackspaceIconPose.SWING_WORD_DEGREES)
            sparks.burst(x, y, WORD_SPARKS, WORD_RADIUS_DP, WORD_LIFE_S)
        }
        lastWords = words
    }

    /** Doigt relâché après un glissement : la suppression part, une gerbe s'envole vers la gauche et l'icône oscille en se redressant. */
    fun onSwipeRelease(x: Float, y: Float) {
        tiltTarget = 0f
        if (!animationsEnabled()) return
        startSwing(BackspaceIconPose.SWING_RELEASE_DEGREES)
        sparks.burst(x, y, RELEASE_SPARKS, RELEASE_RADIUS_DP, RELEASE_LIFE_S, RELEASE_LEFT_BIAS_DP)
    }

    /** Geste interrompu par le système : l'icône se redresse, sans étincelles. */
    fun onSwipeCancel() {
        tiltTarget = 0f
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
        if (swingTime >= 0f) {
            swingTime += dt
            if (swingTime >= BackspaceIconPose.SWING_DURATION_S) swingTime = -1f
        }
        tilt.step(tiltTarget, dt)
        if (tilt.isSettledAngle(tiltTarget)) tilt.snap(tiltTarget)
        iconAngle = BackspaceIconPose.swingAngle(swingTime, swingAmplitude) + tilt.value
        sparks.step(dt)
    }

    /** Coupe tout (vue détachée, changement de clavier). */
    fun cancel() {
        swingTime = -1f
        tiltTarget = 0f
        tilt.snap(0f)
        iconAngle = 0f
        sparks.clear()
        lastNanos = 0L
    }

    val capacity: Int get() = sparks.capacity

    fun isSparkAlive(i: Int): Boolean = sparks.isAlive(i)

    fun sparkAlpha(i: Int): Float = sparks.alpha(i)

    fun sparkRadius(i: Int): Float = sparks.radius(i)

    private fun startSwing(amplitude: Float) {
        swingTime = 0f
        swingAmplitude = amplitude
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MAX_FRAME_S = 0.05f
        private const val REPEAT_RESTART_S = 0.25f
        private const val REPEAT_BURST_GAP_NANOS = 150_000_000L
        private const val TAP_SPARKS = 5
        private const val TAP_RADIUS_DP = 28f
        private const val TAP_LIFE_S = 0.48f
        private const val TRAIL_STEP_DP = 12f
        private const val TRAIL_SPARKS = 2
        private const val TRAIL_SPREAD_DP = 8f
        private const val TRAIL_FALL_DP = 16f
        private const val TRAIL_DRIFT_MIN_DP = 6f
        private const val TRAIL_DRIFT_MAX_DP = 24f
        private const val TRAIL_LIFE_S = 0.48f
        private const val WORD_SPARKS = 4
        private const val WORD_RADIUS_DP = 24f
        private const val WORD_LIFE_S = 0.45f
        private const val RELEASE_SPARKS = 8
        private const val RELEASE_RADIUS_DP = 36f
        private const val RELEASE_LIFE_S = 0.55f
        private const val RELEASE_LEFT_BIAS_DP = 12f
    }
}
