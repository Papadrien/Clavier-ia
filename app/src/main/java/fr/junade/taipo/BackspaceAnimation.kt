package fr.junade.taipo

/**
 * État de l'animation de l'icône retour arrière du clavier : balancier à chaque suppression, inclinaison pendant le
 * glissement vers la gauche (aucune étincelle). Le texte et le curseur appartiennent à l'application hôte : rien n'est animé dessus, tout se
 * passe dans le clavier. La touche elle-même n'est pas animée.
 *
 * Aucune dépendance à Android pour la logique (testée en JVM) : [KeyboardView] appelle [advance] une fois par image avant de
 * dessiner et lit [iconAngle] pour [KeyRenderer]. Si les animations système sont coupées
 * ([animationsEnabled] faux), rien ne se déclenche.
 */
internal class BackspaceAnimation(
    private val density: Float,
    private val animationsEnabled: () -> Boolean,
) {

    /** Angle courant de l'icône en degrés (balancier + inclinaison), mis à jour par [advance]. */
    var iconAngle = 0f
        private set

    val isRunning: Boolean
        get() = swingTime >= 0f || !tilt.isSettledAngle(tiltTarget)

    private var swingTime = -1f
    private var swingAmplitude = 0f
    private val tilt = SpringValue()
    private var tiltTarget = 0f
    private var lastNanos = 0L
    private var lastWords = 0

    /** Une touche retour arrière vient d'être saisie. [repeat] : répétition d'un appui maintenu. */
    fun onKey(repeat: Boolean) {
        if (!animationsEnabled()) return
        if (repeat) {
            if (swingTime < 0f || swingTime > REPEAT_RESTART_S) startSwing(BackspaceIconPose.SWING_REPEAT_DEGREES)
        } else {
            startSwing(BackspaceIconPose.SWING_TAP_DEGREES)
        }
    }

    /** Doigt posé sur la touche : un nouveau geste commence. */
    fun onPress() {
        lastWords = 0
        tiltTarget = 0f
    }

    /**
     * Le glissement vers la gauche se poursuit : le doigt est à [distancePx] de son point d'appui, et [words] mots sont
     * désormais surlignés dans l'application. L'icône s'incline et oscille à chaque mot.
     */
    fun onDrag(distancePx: Float, words: Int) {
        if (!animationsEnabled()) return
        tiltTarget = BackspaceIconPose.dragTilt(distancePx / density)
        if (words > lastWords) startSwing(BackspaceIconPose.SWING_WORD_DEGREES)
        lastWords = words
    }

    /** Doigt relâché après un glissement : la suppression part et l'icône oscille en se redressant. */
    fun onSwipeRelease() {
        tiltTarget = 0f
        if (!animationsEnabled()) return
        startSwing(BackspaceIconPose.SWING_RELEASE_DEGREES)
    }

    /** Geste interrompu par le système : l'icône se redresse. */
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
    }

    /** Coupe tout (vue détachée, changement de clavier). */
    fun cancel() {
        swingTime = -1f
        tiltTarget = 0f
        tilt.snap(0f)
        iconAngle = 0f
        lastNanos = 0L
    }

    private fun startSwing(amplitude: Float) {
        swingTime = 0f
        swingAmplitude = amplitude
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val MAX_FRAME_S = 0.05f
        private const val REPEAT_RESTART_S = 0.25f
    }
}
