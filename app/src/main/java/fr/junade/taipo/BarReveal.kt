package fr.junade.taipo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.AnticipateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** Entrée en ressort (actions IA après un appui sur le robot) : durée, décalage entre deux boutons, départ. */
    const val SPRING_DURATION_MS = 620L
    const val SPRING_STAGGER_MS = 45L
    const val SPRING_SLIDE_DP = 44f
    const val SPRING_START_SCALE = 0.55f

    /** Ressort sous-amorti : ratio d'amortissement (≈ 16 % de dépassement) et pulsation propre (rad/s). */
    private const val SPRING_DAMPING = 0.5f
    private const val SPRING_OMEGA = 20f

    /** Part de la durée sur laquelle l'opacité monte de 0 à son niveau de repos. */
    private const val SPRING_FADE_FRACTION = 0.3f

    /** Sortie hors écran (bouton menu de gauche) : durée, intensité du recul initial, début du fondu (part de la durée). */
    const val EXIT_DURATION_MS = 280L
    private const val EXIT_ANTICIPATION = 1.6f
    private const val EXIT_FADE_START = 0.6f

    private val easing by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) } // paresseux : l'objet reste utilisable en test JVM
    private val running = WeakHashMap<View, Animator>()
    private val exiting = WeakHashMap<View, Animator>()

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

    /**
     * Avancement d'un ressort amorti (masse 1, partant de 0 au repos vers 1) à la [fraction] (0 à 1) de la durée
     * [SPRING_DURATION_MS] : dépasse 1 d'environ 16 % puis oscille jusqu'à se poser. Réponse analytique d'un ressort
     * sous-amorti, donc sans état et testable en JVM.
     */
    fun springProgress(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        val t = x * (SPRING_DURATION_MS / 1000f)
        val damped = SPRING_DAMPING * SPRING_OMEGA
        val omegaD = SPRING_OMEGA * sqrt(1f - SPRING_DAMPING * SPRING_DAMPING)
        val envelope = exp(-damped * t)
        return 1f - envelope * (cos(omegaD * t) + damped / omegaD * sin(omegaD * t))
    }

    /** Délai de départ de la vue de rang [order] pour l'entrée en ressort (0 = la plus proche du bouton touché). */
    fun springStartDelay(order: Int): Long = order * SPRING_STAGGER_MS

    /**
     * Apparition « ressort » de [view] (actions IA après un appui sur le robot) : elle jaillit du côté du bouton touché
     * ([fromEnd] : à droite, sinon à gauche), grossit de [SPRING_START_SCALE] à 1 et se pose en rebondissant. L'opacité
     * monte vite (sans dépasser celle de repos), le dépassement ne concerne que la position et la taille. [offsetPx] :
     * distance de départ ; [restingAlpha] : comme pour [enter]. Aucune animation si le système les a coupées.
     */
    fun enterSpring(
        view: View,
        fromEnd: Boolean,
        order: Int,
        offsetPx: Float,
        restingAlpha: (() -> Float)? = null,
    ) {
        cancel(view)
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val baseAlpha = restingAlpha?.invoke() ?: view.alpha
        val direction = if (fromEnd) 1f else -1f
        view.alpha = 0f
        view.translationX = direction * offsetPx
        view.scaleX = SPRING_START_SCALE
        view.scaleY = SPRING_START_SCALE
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SPRING_DURATION_MS
            startDelay = springStartDelay(order)
            interpolator = LinearInterpolator()
            addUpdateListener { update ->
                val fraction = update.animatedValue as Float
                val progress = springProgress(fraction)
                view.alpha = (restingAlpha?.invoke() ?: baseAlpha) * (fraction / SPRING_FADE_FRACTION).coerceIn(0f, 1f)
                view.translationX = direction * offsetPx * (1f - progress)
                val scale = SPRING_START_SCALE + (1f - SPRING_START_SCALE) * progress
                view.scaleX = scale
                view.scaleY = scale
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        running.remove(view)
                        view.alpha = restingAlpha?.invoke() ?: baseAlpha
                        view.translationX = 0f
                        view.scaleX = 1f
                        view.scaleY = 1f
                    }
                },
            )
        }
        running[view] = animator
        animator.start()
    }

    /** [view] est en train de sortir par [exitOffscreen] (elle reste affichée jusqu'à la fin de la sortie). */
    fun isExiting(view: View): Boolean = exiting.containsKey(view)

    /**
     * Sortie de [view] vers la gauche (ou la droite quand [toEnd]) sur [distancePx], avec un léger recul avant de partir
     * (effet ressort), puis la vue passe [View.GONE]. Une interruption ([cancel], nouvelle animation) termine aussitôt la
     * sortie : la vue est alors masquée et remise à l'état normal, prête à ré-apparaître.
     */
    fun exitOffscreen(view: View, distancePx: Float, toEnd: Boolean = false) {
        cancel(view)
        val baseAlpha = view.alpha
        val direction = if (toEnd) 1f else -1f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.visibility = View.GONE
            return
        }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXIT_DURATION_MS
            interpolator = AnticipateInterpolator(EXIT_ANTICIPATION)
            addUpdateListener { update ->
                val progress = update.animatedValue as Float
                view.translationX = direction * distancePx * progress
                view.alpha = baseAlpha * (1f - (update.animatedFraction - EXIT_FADE_START).coerceAtLeast(0f) / (1f - EXIT_FADE_START))
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        running.remove(view)
                        exiting.remove(view)
                        view.visibility = View.GONE
                        view.alpha = baseAlpha
                        view.translationX = 0f
                    }
                },
            )
        }
        running[view] = animator
        exiting[view] = animator
        animator.start()
    }

    /** Interrompt l'animation de [view] et la rend à son état normal (sans effet si aucune n'est en cours). */
    fun cancel(view: View) {
        running[view]?.cancel() // onAnimationEnd (appelé aussi à l'annulation) remet la vue en état
    }
}
