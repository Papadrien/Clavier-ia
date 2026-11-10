package fr.junade.taipo

import kotlin.math.sqrt

/**
 * Animations du clavier flottant (maquettes validées le 10/11/2026), toutes en ressort :
 * - **Envol / Atterrissage** ([morphProgress]) : 0 = clavier ancré, 1 = carte flottante. Position, largeur, coins, ombre, marge du
 *   bas et poignée suivent cette seule valeur, qui dépasse un peu 1 (ou 0) avant de se poser ;
 * - **Soulever** ([liftScale]) : la carte grossit un peu, avec un petit rebond, tant que le doigt tient la poignée ;
 * - **Zone d'ancrage** ([dockZoneAlpha]) : apparaît quand la carte est poussée sous le bas de l'écran ;
 * - **Apparition sur place** ([appearScale], [appearAlpha]) : à l'ouverture d'un champ, la carte grossit avec un petit rebond
 *   pendant qu'elle devient opaque, là où elle a été laissée.
 *
 * Animations système coupées ([animationsEnabled] faux) : tout est posé directement, sans mouvement. Logique pure, testée en JVM
 * (`FloatingTransitionTest`) ; la vue appelle [step] à chaque image tant que [isRunning].
 */
internal class FloatingTransition(private val animationsEnabled: () -> Boolean) {

    private val morph = OneHandedSpringValue(MORPH_STIFFNESS, damping(MORPH_STIFFNESS, MORPH_DAMPING_RATIO))
    private val lift = OneHandedSpringValue(LIFT_STIFFNESS, damping(LIFT_STIFFNESS, LIFT_DAMPING_RATIO))
    private val armed = OneHandedSpringValue(ARMED_STIFFNESS, damping(ARMED_STIFFNESS, ARMED_DAMPING_RATIO))
    private val appear = OneHandedSpringValue(APPEAR_STIFFNESS, damping(APPEAR_STIFFNESS, APPEAR_DAMPING_RATIO), value = 1f)

    /** 0 = ancré, 1 = flottant ; peut dépasser un peu pendant le rebond. */
    val morphProgress: Float get() = morph.value

    /** Avancement borné à [0, 1], pour ce qui ne doit pas dépasser (ombre, poignée). */
    val morphFraction: Float get() = morph.value.coerceIn(0f, 1f)

    val liftScale: Float get() = 1f + LIFT_SCALE * lift.value
    val liftShadow: Float get() = 1f + LIFT_SHADOW * lift.value.coerceAtLeast(0f)
    val dockZoneAlpha: Float get() = armed.value.coerceIn(0f, 1f)
    val appearScale: Float get() = APPEAR_FROM_SCALE + (1f - APPEAR_FROM_SCALE) * appear.value
    val appearAlpha: Float get() = (appear.value * APPEAR_ALPHA_RATE).coerceIn(0f, 1f)

    /** Vrai tant que la carte passe d'une forme à l'autre (la fenêtre doit alors couvrir l'écran). */
    val isMorphing: Boolean get() = !morph.isSettled

    val isRunning: Boolean get() = !(morph.isSettled && lift.isSettled && armed.isSettled && appear.isSettled)

    /** Nouvelle forme visée ; sans [animate] (ou animations coupées), posée directement. */
    fun setFloating(floating: Boolean, animate: Boolean) {
        val target = if (floating) 1f else 0f
        if (animate && animationsEnabled()) {
            morph.retarget(target, 0f, MORPH_STIFFNESS, damping(MORPH_STIFFNESS, MORPH_DAMPING_RATIO))
        } else {
            morph.snap(target)
        }
    }

    fun setLifted(lifted: Boolean) = retargetOrSnap(lift, if (lifted) 1f else 0f, LIFT_STIFFNESS, LIFT_DAMPING_RATIO)

    fun setArmed(isArmed: Boolean) = retargetOrSnap(armed, if (isArmed) 1f else 0f, ARMED_STIFFNESS, ARMED_DAMPING_RATIO)

    /** Ouverture d'un champ en mode flottant : la carte apparaît sur place. */
    fun playAppear() {
        if (!animationsEnabled()) {
            appear.snap(1f)
            return
        }
        appear.snap(0f)
        appear.retarget(1f, 0f, APPEAR_STIFFNESS, damping(APPEAR_STIFFNESS, APPEAR_DAMPING_RATIO))
    }

    fun step(dtSeconds: Float) {
        if (dtSeconds <= 0f) return
        morph.step(dtSeconds)
        lift.step(dtSeconds)
        armed.step(dtSeconds)
        appear.step(dtSeconds)
        if (morph.isSettled) morph.snap(morph.target)
        if (lift.isSettled) lift.snap(lift.target)
        if (armed.isSettled) armed.snap(armed.target)
        if (appear.isSettled) appear.snap(appear.target)
    }

    private fun retargetOrSnap(spring: OneHandedSpringValue, target: Float, stiffness: Float, ratio: Float) {
        if (animationsEnabled()) spring.retarget(target, 0f, stiffness, damping(stiffness, ratio)) else spring.snap(target)
    }

    companion object {
        /** Envol et atterrissage : ressort 380, amortissement 0,78 (léger dépassement, posé en ≈ 0,32 s). */
        const val MORPH_STIFFNESS = 380f
        const val MORPH_DAMPING_RATIO = 0.78f

        /** Soulever : ressort 600, amortissement 0,5 (petit rebond), carte agrandie de 4 %, ombre 60 % plus marquée. */
        const val LIFT_STIFFNESS = 600f
        const val LIFT_DAMPING_RATIO = 0.5f
        const val LIFT_SCALE = 0.04f
        const val LIFT_SHADOW = 0.6f

        /** Zone d'ancrage : franche, sans rebond visible. */
        const val ARMED_STIFFNESS = 700f
        const val ARMED_DAMPING_RATIO = 0.9f

        /** Apparition sur place : de 88 % à 100 %, ressort 520, amortissement 0,55 ; opaque dès 60 % du trajet. */
        const val APPEAR_STIFFNESS = 520f
        const val APPEAR_DAMPING_RATIO = 0.55f
        const val APPEAR_FROM_SCALE = 0.88f
        const val APPEAR_ALPHA_RATE = 1.6f

        /** Amortissement d'un ressort à masse 1 pour un ratio donné (comme SpringForce d'Android). */
        fun damping(stiffness: Float, ratio: Float): Float = 2f * ratio * sqrt(stiffness)
    }
}
