package fr.junade.taipo

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Ressort amorti à raideur et amortissement réglables (le [SpringValue] partagé a des constantes fixes). [retarget] fixe une nouvelle cible,
 * éventuellement après un délai ; la valeur garde sa vitesse, donc un changement de cible en plein mouvement ne fait pas de saut.
 */
internal class TunedSpring(
    private var stiffness: Float,
    private var damping: Float,
    value: Float = 0f,
    private val distanceTolerance: Float = 0.002f,
    private val speedTolerance: Float = 0.01f,
) {
    var value = value
        private set
    var velocity = 0f
        private set
    var target = value
        private set
    private var delayS = 0f

    fun retarget(newTarget: Float, delaySeconds: Float, newStiffness: Float, newDamping: Float) {
        target = newTarget
        delayS = delaySeconds
        stiffness = newStiffness
        damping = newDamping
    }

    fun snap(newValue: Float) {
        value = newValue
        target = newValue
        velocity = 0f
        delayS = 0f
    }

    fun step(dtSeconds: Float) {
        var remaining = dtSeconds
        if (delayS > 0f) {
            if (delayS >= remaining) {
                delayS -= remaining
                return
            }
            remaining -= delayS
            delayS = 0f
        }
        val n = ceil(remaining / SUBSTEP_S).toInt().coerceAtLeast(1)
        val h = remaining / n
        repeat(n) {
            velocity += (stiffness * (target - value) - damping * velocity) * h
            value += velocity * h
        }
    }

    val isSettled: Boolean
        get() = delayS <= 0f && abs(target - value) < distanceTolerance && abs(velocity) < speedTolerance

    private companion object {
        const val SUBSTEP_S = 0.004f
    }
}

/**
 * Réglages du style « Ressort » du mode une main (raideur, amortissement). Chaque ressort est celui de la maquette validée (zone 170 / 15,
 * apparition 380 / 15, retournement 300 / 16, rebond de l'appui 380 / 13) réglé pour poser 30 % plus vite avec 20 % de dépassement en
 * moins. Zone des touches : dépassement 8,7 % (au lieu de 10,9 %), posée en 0,32 s (au lieu de 0,45 s).
 */
internal object OneHandedSpring {
    const val ZONE_STIFFNESS = 349f
    const val ZONE_DAMPING = 22.8f

    /** Apparition des boutons ronds (petit rebond), disparition (presque sans rebond) et retournement de la flèche. */
    const val APPEAR_STIFFNESS = 751f
    const val APPEAR_DAMPING = 24f
    const val EXIT_STIFFNESS = 710f
    const val EXIT_DAMPING = 44f
    const val FLIP_STIFFNESS = 552f
    const val FLIP_DAMPING = 23.9f

    /** Appui sur un bouton rond : il s'écrase vite, puis revient avec un rebond au relâchement. */
    const val PRESS_DOWN_STIFFNESS = 1014f
    const val PRESS_DOWN_DAMPING = 39f
    const val PRESS_UP_STIFFNESS = 440f
    const val PRESS_UP_DAMPING = 16.4f
    const val PRESSED_SCALE = 0.86f

    /** À la sortie du mode, les boutons rétrécissent d'abord ; à l'entrée, ils apparaissent une fois le clavier en route. */
    const val EXIT_ZONE_DELAY_S = 0.08f
    const val APPEAR_DELAY_S = 0.12f
    const val SIDE_DELAY_S = 0.04f
}

/**
 * Animation du mode une main : la zone des touches (position et largeur) suit un ressort quand le mode s'active, change de côté ou se
 * quitte, et les deux boutons ronds de la zone libre apparaissent, se déplacent (flèche retournée) et disparaissent en ressort.
 * Calculs purs, testables en JVM ; [KeyboardView] lit la pose à chaque image et pilote l'horloge via [advance].
 */
internal class OneHandedTransition {

    private val left = TunedSpring(OneHandedSpring.ZONE_STIFFNESS, OneHandedSpring.ZONE_DAMPING, 0f, 2f, 40f)
    private val width = TunedSpring(OneHandedSpring.ZONE_STIFFNESS, OneHandedSpring.ZONE_DAMPING, 0f, 2f, 40f)
    private val controlsX = TunedSpring(OneHandedSpring.ZONE_STIFFNESS, OneHandedSpring.ZONE_DAMPING, 0f, 2f, 40f)
    private val controlsScale = TunedSpring(OneHandedSpring.APPEAR_STIFFNESS, OneHandedSpring.APPEAR_DAMPING)
    private val flip = TunedSpring(OneHandedSpring.FLIP_STIFFNESS, OneHandedSpring.FLIP_DAMPING, 1f)
    private val press = Array(OneHandedControls.Button.entries.size) {
        TunedSpring(OneHandedSpring.PRESS_UP_STIFFNESS, OneHandedSpring.PRESS_UP_DAMPING, 1f)
    }
    private val moving = listOf(left, width, controlsX, controlsScale, flip)
    private val all = moving + press
    private var lastNanos = 0L

    /** Zone des touches à cet instant (dépasse un peu sa cible pendant le ressort). */
    val zone: KeyboardWidth get() = KeyboardWidth(left.value, width.value)

    val controlsCx: Float get() = controlsX.value

    /** Échelle d'apparition des boutons ronds : 0 = absents, 1 = en place ; dépasse 1 un instant. */
    val controlsAppear: Float get() = controlsScale.value.coerceAtLeast(0f)

    /** Facteur horizontal de la flèche de changement de côté : 1 = vers la droite, -1 = vers la gauche, 0 en plein retournement. */
    val flipX: Float get() = flip.value

    fun pressScale(button: OneHandedControls.Button): Float = press[button.ordinal].value.coerceAtLeast(0f)

    /** Vrai tant que la zone ou les boutons bougent : la vue lit alors la pose ici plutôt que la géométrie fixe du mode. */
    val isMoving: Boolean get() = moving.any { !it.isSettled }

    /** Vrai tant qu'un ressort (zone, boutons ou appui) n'est pas posé : la vue redessine à chaque image. */
    val isRunning: Boolean get() = all.any { !it.isSettled }

    /**
     * Lance le passage de [previous] à [mode]. [from] et [to] sont les zones fixes des deux modes, [fromCx] et [toCx] les centres des
     * boutons dans leur zone libre. Si un mouvement est déjà en cours, il continue depuis sa position et sa vitesse actuelles.
     */
    fun moveTo(previous: OneHandedMode, mode: OneHandedMode, from: KeyboardWidth, to: KeyboardWidth, fromCx: Float, toCx: Float) {
        if (!isMoving) {
            left.snap(from.leftPx)
            width.snap(from.widthPx)
            controlsX.snap(fromCx)
            controlsScale.snap(if (previous.isActive) 1f else 0f)
            flip.snap(sign(if (previous.isActive) previous else mode))
        }
        val zoneDelay = if (mode.isActive) 0f else OneHandedSpring.EXIT_ZONE_DELAY_S
        left.retarget(to.leftPx, zoneDelay, OneHandedSpring.ZONE_STIFFNESS, OneHandedSpring.ZONE_DAMPING)
        width.retarget(to.widthPx, zoneDelay, OneHandedSpring.ZONE_STIFFNESS, OneHandedSpring.ZONE_DAMPING)
        when {
            !mode.isActive ->
                controlsScale.retarget(0f, 0f, OneHandedSpring.EXIT_STIFFNESS, OneHandedSpring.EXIT_DAMPING)
            !previous.isActive && controlsScale.value < APPEAR_RESET_BELOW -> {
                controlsX.snap(toCx)
                flip.snap(sign(mode))
                controlsScale.retarget(1f, OneHandedSpring.APPEAR_DELAY_S, OneHandedSpring.APPEAR_STIFFNESS, OneHandedSpring.APPEAR_DAMPING)
            }
            else -> {
                controlsX.retarget(toCx, OneHandedSpring.SIDE_DELAY_S, OneHandedSpring.ZONE_STIFFNESS, OneHandedSpring.ZONE_DAMPING)
                flip.retarget(sign(mode), OneHandedSpring.SIDE_DELAY_S, OneHandedSpring.FLIP_STIFFNESS, OneHandedSpring.FLIP_DAMPING)
                controlsScale.retarget(1f, 0f, OneHandedSpring.APPEAR_STIFFNESS, OneHandedSpring.APPEAR_DAMPING)
            }
        }
        lastNanos = 0L
    }

    /** Appui ([pressed]) ou relâchement d'un bouton rond : il s'écrase puis rebondit. [animate] faux : changement direct. */
    fun setPressed(button: OneHandedControls.Button, pressed: Boolean, animate: Boolean) {
        val spring = press[button.ordinal]
        val goal = if (pressed) OneHandedSpring.PRESSED_SCALE else 1f
        when {
            !animate -> spring.snap(goal)
            pressed -> spring.retarget(goal, 0f, OneHandedSpring.PRESS_DOWN_STIFFNESS, OneHandedSpring.PRESS_DOWN_DAMPING)
            else -> spring.retarget(goal, 0f, OneHandedSpring.PRESS_UP_STIFFNESS, OneHandedSpring.PRESS_UP_DAMPING)
        }
    }

    /** Fait avancer les ressorts jusqu'à [nowNanos] ; une fois tout posé, les valeurs sont ramenées exactement sur leurs cibles. */
    fun advance(nowNanos: Long) {
        if (lastNanos == 0L) {
            lastNanos = nowNanos
            return
        }
        val dt = ((nowNanos - lastNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastNanos = nowNanos
        all.forEach { it.step(dt) }
        if (!isRunning) stop()
    }

    /** Termine tout de suite : chaque ressort saute sur sa cible. */
    fun stop() {
        all.forEach { it.snap(it.target) }
        lastNanos = 0L
    }

    private fun sign(mode: OneHandedMode): Float = if (mode == OneHandedMode.LEFT) 1f else -1f

    private companion object {
        const val APPEAR_RESET_BELOW = 0.05f
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MAX_FRAME_S = 0.05f
    }
}
