package fr.junade.taipo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.LinearInterpolator
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
 * - [glideX] : un bouton déjà affiché qui change de place (espacement égal de la rangée) glisse en ressort jusqu'à sa nouvelle position.
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

    /**
     * Entrée en ressort (actions IA après un appui sur le robot) : distance et taille de départ. La durée, le décalage entre deux
     * boutons et le ressort lui-même viennent du style d'animation ([MotionStyle.current] : [springDurationMs], [springProgress]).
     */
    const val SPRING_SLIDE_DP = 44f
    const val SPRING_START_SCALE = 0.55f

    /** Durée de l'entrée en ressort et des glissements, selon le style d'animation (620 ms en Ressort). */
    val springDurationMs: Long get() = MotionStyle.current.revealDurationMs

    /** Écrasement / étirement des boutons qui apparaissent (Gelée) : modéré, ce sont de petites vues côte à côte. */
    private const val JELLY_STRENGTH = 0.7f

    /** Part de la durée sur laquelle l'opacité monte de 0 à son niveau de repos. */
    private const val SPRING_FADE_FRACTION = 0.3f

    private val easing by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) } // paresseux : l'objet reste utilisable en test JVM
    private val running = WeakHashMap<View, Animator>()
    private val gliding = WeakHashMap<View, Animator>()

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
            val scale = MotionStyle.current.curtainScale
            duration = ((if (full) DURATION_MS else QUIET_DURATION_MS) * scale).toLong()
            startDelay = (startDelay(order, full) * scale).toLong()
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
     * [MotionStyle.revealDurationMs] de [style] : en Ressort, dépasse 1 d'environ 16 % puis oscille jusqu'à se poser ; en Vif,
     * s'approche de 1 sans le dépasser. Réponse analytique ([MotionStyle.response]), donc sans état et testable en JVM.
     */
    fun springProgress(fraction: Float, style: MotionStyle = MotionStyle.current): Float {
        val t = fraction.coerceIn(0f, 1f) * (style.revealDurationMs / 1000f)
        return style.response(t, style.revealZeta)
    }

    /** Délai de départ de la vue de rang [order] pour l'entrée en ressort (0 = la plus proche du bouton touché). */
    fun springStartDelay(order: Int): Long = order * MotionStyle.current.revealStaggerMs

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
        val style = MotionStyle.current
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = style.revealDurationMs
            startDelay = springStartDelay(order)
            interpolator = LinearInterpolator()
            addUpdateListener { update ->
                val fraction = update.animatedValue as Float
                val progress = springProgress(fraction, style)
                view.alpha = (restingAlpha?.invoke() ?: baseAlpha) * (fraction / SPRING_FADE_FRACTION).coerceIn(0f, 1f)
                view.translationX = direction * offsetPx * (1f - progress)
                val scale = SPRING_START_SCALE + (1f - SPRING_START_SCALE) * progress
                // Gelée : le bouton s'élargit et s'aplatit quand il dépasse sa taille, s'étire avant de l'atteindre.
                view.scaleX = scale * style.stretchX(progress, JELLY_STRENGTH)
                view.scaleY = scale * style.stretchY(progress, JELLY_STRENGTH)
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

    /** Décalage horizontal restant (px) d'un glissement en ressort parti de [fromPx] à la [fraction] (0 à 1) de la durée. */
    fun glideOffset(fromPx: Float, fraction: Float, style: MotionStyle = MotionStyle.current): Float =
        fromPx * (1f - springProgress(fraction, style))

    /** [view] joue une animation d'apparition (ressort ou rideau) : elle ne glisse pas en plus, son décalage est déjà animé. */
    fun isEntering(view: View): Boolean = running.containsKey(view) && !gliding.containsKey(view)

    /**
     * [view] vient d'être placée à un autre endroit par la rangée : elle part de [fromPx] (écart entre son ancienne et sa
     * nouvelle position, négatif si elle venait de la gauche) et glisse en ressort jusqu'à 0. Un glissement en cours est
     * remplacé : l'écart donné doit déjà tenir compte de son décalage courant. Aucune animation si le système les a coupées.
     */
    fun glideX(view: View, fromPx: Float) {
        cancel(view)
        if (!ValueAnimator.areAnimatorsEnabled() || fromPx == 0f) return
        view.translationX = fromPx
        val style = MotionStyle.current
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = style.revealDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { update -> view.translationX = glideOffset(fromPx, update.animatedValue as Float, style) }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        running.remove(view)
                        gliding.remove(view)
                        view.translationX = 0f
                    }
                },
            )
        }
        running[view] = animator
        gliding[view] = animator
        animator.start()
    }

    /** Interrompt l'animation de [view] et la rend à son état normal (sans effet si aucune n'est en cours). */
    fun cancel(view: View) {
        running[view]?.cancel() // onAnimationEnd (appelé aussi à l'annulation) remet la vue en état
    }
}
