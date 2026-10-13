package fr.junade.taipo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Animation de « dévoilement » de la barre du haut : un bouton ou une zone qui apparaît s'ouvre comme un rideau à
 * partir du bouton touché (rognage animé), glisse de quelques pixels vers sa place et se fond, en décélération.
 *
 * - [enter] : mode complet (340 ms, décalage de 30 ms entre deux boutons) après un appui sur le menu de gauche ou
 *   le menu robot de droite ; mode discret (fondu de 160 ms) pour les changements dus à la frappe ou à une copie, afin
 *   que la barre ne ralentisse jamais la saisie.
 * - Un nouvel appel, ou [cancel], interrompt l'animation en cours et rend la vue à son état normal (opacité d'origine,
 *   pas de rognage, pas de décalage) : un second appui inverse donc le mouvement sans saut.
 * - Aucune animation si le système a coupé les animations (échelle de durée à 0).
 *
 * Les calculs ([visibleSpan], [slideOffset]) sont purs et testés en JVM.
 */
internal object BarReveal {

    const val DURATION_MS = 340L
    const val STAGGER_MS = 30L
    const val FIRST_DELAY_MS = 40L
    const val QUIET_DURATION_MS = 160L

    /** Distance de glissement au départ, en dp. */
    const val SLIDE_DP = 18f

    private val easing by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) } // paresseux : l'objet reste utilisable en test JVM
    private val running = WeakHashMap<View, Animator>()

    /**
     * Bornes horizontales (gauche, droite) de la partie visible d'une vue de [width] px à l'avancement [progress]
     * (0 à 1) : le rideau part du bord gauche, ou du bord droit quand [fromEnd].
     */
    fun visibleSpan(width: Int, progress: Float, fromEnd: Boolean): Pair<Int, Int> {
        val shown = (width * progress.coerceIn(0f, 1f)).roundToInt()
        return if (fromEnd) (width - shown) to width else 0 to shown
    }

    /** Décalage horizontal restant (px) : négatif quand la vue vient de la gauche, positif quand elle vient de la droite. */
    fun slideOffset(slidePx: Float, progress: Float, fromEnd: Boolean): Float {
        val remaining = slidePx * (1f - progress.coerceIn(0f, 1f))
        return if (fromEnd) remaining else -remaining
    }

    /** Délai de départ de la vue de rang [order] (0 = la plus proche du bouton touché). */
    fun startDelay(order: Int, full: Boolean): Long = if (full) FIRST_DELAY_MS + order * STAGGER_MS else 0L

    /**
     * Anime l'apparition de [view], qui vient de passer visible. [fromEnd] : le bouton touché est à droite de la vue ;
     * [order] : rang dans la cascade ; [full] : dévoilement complet, sinon simple fondu. [restingAlpha] : opacité de
     * repos de la vue, relue à chaque image et à la fin, pour une vue dont l'opacité change d'elle-même (bouton grisé
     * dans un champ sensible) ; sans elle, l'opacité du départ est mémorisée puis rétablie, ce qui figerait une
     * opacité devenue périmée pendant l'animation, puis la mémoriserait de nouveau à chaque apparition.
     */
    fun enter(view: View, fromEnd: Boolean, order: Int = 0, full: Boolean = true, restingAlpha: (() -> Float)? = null) {
        cancel(view)
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val baseAlpha = restingAlpha?.invoke() ?: view.alpha
        val slidePx = SLIDE_DP * view.resources.displayMetrics.density
        view.alpha = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (full) DURATION_MS else QUIET_DURATION_MS
            startDelay = startDelay(order, full)
            interpolator = easing
            addUpdateListener { update ->
                val progress = update.animatedValue as Float
                view.alpha = (restingAlpha?.invoke() ?: baseAlpha) * progress
                if (full) {
                    val width = view.width
                    if (width > 0) {
                        val (left, right) = visibleSpan(width, progress, fromEnd)
                        view.clipBounds = Rect(left, 0, right, view.height)
                    }
                    view.translationX = slideOffset(slidePx, progress, fromEnd)
                }
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        running.remove(view)
                        view.alpha = restingAlpha?.invoke() ?: baseAlpha
                        view.clipBounds = null
                        view.translationX = 0f
                    }
                },
            )
        }
        running[view] = animator
        animator.start()
    }

    /** Interrompt l'animation de [view] et la rend à son état normal (sans effet si aucune n'est en cours). */
    fun cancel(view: View) {
        running[view]?.cancel() // onAnimationEnd (appelé aussi à l'annulation) remet la vue en état
    }
}
