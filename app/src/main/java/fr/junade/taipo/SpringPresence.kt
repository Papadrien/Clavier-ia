package fr.junade.taipo

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Calculs purs de l'apparition et de la disparition en ressort (testés en JVM). [progress] : 0 = écartée de
 * [distancePx] vers le bas et transparente, 1 = à sa place ; le ressort dépasse 1 un instant à l'apparition.
 */
internal object SpringPresencePose {

    /** L'opacité atteint 1 dès la moitié de la course : la vue est nette avant d'avoir fini de monter. */
    const val FADE_GAIN = 2f

    /** À la disparition le ressort tourne un peu plus lentement, pour que le mouvement se lise. */
    const val EXIT_TIME_SCALE = 0.75f

    /** Garde-fou : une disparition ne dure jamais plus que cela (secondes), même si les images tardent. */
    const val EXIT_MAX_S = 0.6f

    fun translationY(progress: Float, distancePx: Float): Float = (1f - progress) * distancePx

    fun alpha(progress: Float): Float = (progress * FADE_GAIN).coerceIn(0f, 1f)

    /** La disparition est finie quand la vue a retrouvé le bas (progression nulle) ou après [EXIT_MAX_S]. */
    fun exitFinished(progress: Float, elapsedSeconds: Float): Boolean = progress <= 0f || elapsedSeconds >= EXIT_MAX_S
}

/**
 * Apparition et disparition d'une vue avec un effet ressort : à l'apparition elle monte depuis le bas en se fondant,
 * dépasse un peu sa place puis s'y pose ; à la disparition elle fait le chemin inverse, puis seulement devient `GONE`
 * (elle reste donc affichée, et garde sa place dans la mise en page, pendant toute la sortie).
 *
 * Les appels sont idempotents : [show] et [hide] peuvent être rappelés à chaque rendu sans relancer l'animation. Un
 * [show] pendant une sortie (ou un [hide] pendant une entrée) inverse le mouvement sans saut. Animations coupées dans les
 * réglages système : changement de visibilité direct. La vue est remise à plat (pas de décalage, opacité pleine) quand elle
 * est détachée de la fenêtre.
 */
internal class SpringPresence(private val view: View, private val distancePx: () -> Float) {

    private val spring = SpringValue()
    private var ticker: ValueAnimator? = null
    private var lastFrameNanos = 0L
    private var exiting = false
    private var exitSeconds = 0f
    private var afterHidden: (() -> Unit)? = null

    init {
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    if (exiting) finishHide() else {
                        stopTicker()
                        apply(1f)
                    }
                }
            },
        )
    }

    /** Affiche la vue en la faisant monter ; sans effet si elle est déjà affichée. [animate] faux : affichage direct. */
    fun show(animate: Boolean = true) {
        if (exiting) {
            // Réapparition en pleine sortie : le ressort repart de sa position actuelle vers la place.
            exiting = false
            afterHidden = null
            view.visibility = View.VISIBLE
            ensureTicking()
            return
        }
        if (view.visibility == View.VISIBLE) return
        view.visibility = View.VISIBLE
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            stopTicker()
            apply(1f)
            return
        }
        spring.snap(0f)
        apply(0f)
        ensureTicking()
    }

    /**
     * Masque la vue en la faisant redescendre ; sans effet si elle est déjà masquée. [after] est appelé une fois la vue
     * `GONE` (tout de suite si elle l'est déjà ou si [animate] est faux) : c'est là que la vue qui la remplace peut
     * apparaître sans que les deux s'empilent.
     */
    fun hide(animate: Boolean = true, after: (() -> Unit)? = null) {
        if (view.visibility != View.VISIBLE) {
            after?.invoke()
            return
        }
        afterHidden = after ?: afterHidden
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || !view.isLaidOut) {
            finishHide()
            return
        }
        if (exiting) return
        exiting = true
        exitSeconds = 0f
        ensureTicking()
    }

    private fun finishHide() {
        stopTicker()
        exiting = false
        view.visibility = View.GONE
        apply(1f)
        spring.snap(1f)
        val callback = afterHidden
        afterHidden = null
        callback?.invoke()
    }

    private fun ensureTicking() {
        if (ticker != null) return
        lastFrameNanos = System.nanoTime()
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TICK_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { onFrame() }
            start()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun onFrame() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastFrameNanos = now
        if (exiting) {
            exitSeconds += dt
            spring.step(0f, dt * SpringPresencePose.EXIT_TIME_SCALE)
            apply(spring.value)
            if (SpringPresencePose.exitFinished(spring.value, exitSeconds)) finishHide()
        } else {
            spring.step(1f, dt)
            apply(spring.value)
            if (spring.isSettled(1f)) {
                apply(1f)
                stopTicker()
            }
        }
    }

    private fun apply(progress: Float) {
        view.translationY = SpringPresencePose.translationY(progress, distancePx())
        view.alpha = SpringPresencePose.alpha(progress)
    }

    private companion object {
        const val TICK_PERIOD_MS = 1000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
    }
}
