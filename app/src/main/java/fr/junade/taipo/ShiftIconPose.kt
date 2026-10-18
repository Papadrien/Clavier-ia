package fr.junade.taipo

import kotlin.math.abs
import kotlin.math.ceil

/** Les trois états de la touche Maj : minuscule, majuscule pour la prochaine lettre, majuscules verrouillées. */
internal enum class ShiftState {
    OFF,
    ON,
    LOCK,
    ;

    companion object {
        /** Verrouillé prime : le contrôleur laisse alors `isShifted` à vrai. */
        fun of(isShifted: Boolean, isCapsLock: Boolean): ShiftState = when {
            isCapsLock -> LOCK
            isShifted -> ON
            else -> OFF
        }
    }
}

/**
 * Pose de l'icône Maj animée (« Bond »), sur une grille de 24 : calculs purs. La flèche (trait de 2) est creuse en minuscule,
 * pleine en majuscule ; verrouillée, elle se raccourcit un peu et une barre apparaît dessous. Elle s'écrase et s'étire autour
 * de sa base ([PIVOT_Y]). La face de la touche ne change pas : seule l'icône bouge.
 */
internal object ShiftIconPose {

    const val GRID = 24f

    /** Base de la flèche : l'écrasement et l'étirement se font depuis ce point. */
    const val PIVOT_Y = 19f

    /** Hauteur de la flèche verrouillée (14) rapportée à sa hauteur normale (15,5) : mêmes proportions que l'ancienne icône Maj verrouillée. */
    const val LOCK_SCALE_Y = 14f / 15.5f

    /** Pointe de la flèche normale (3,5) et verrouillée (2,5). */
    private const val TIP_Y = 3.5f
    private const val LOCK_TIP_Y = 2.5f

    /** Barre du verrouillage : de x = 9 à 15, à y = 20,5. */
    const val BAR_Y = 20.5f
    const val BAR_LEFT_X = 9f
    const val BAR_RIGHT_X = 15f

    /** Échelle de la barre quand elle n'est pas affichée (elle grandit depuis cette valeur). */
    const val BAR_HIDDEN_SCALE = 0.3f

    /** Décalage vertical de la flèche verrouillée : sa pointe passe de 3,5 à 2,5 une fois raccourcie autour de [PIVOT_Y] (soit −2,5). */
    val LOCK_LIFT: Float = LOCK_TIP_Y - PIVOT_Y - (TIP_Y - PIVOT_Y) * LOCK_SCALE_Y

    fun restScaleY(state: ShiftState): Float = if (state == ShiftState.LOCK) LOCK_SCALE_Y else 1f

    fun restLift(state: ShiftState): Float = if (state == ShiftState.LOCK) LOCK_LIFT else 0f

    /** Remplissage de la flèche : 0 creuse, 1 pleine. */
    fun restFill(state: ShiftState): Float = if (state == ShiftState.OFF) 0f else 1f

    fun restBarOpacity(state: ShiftState): Float = if (state == ShiftState.LOCK) 1f else 0f

    fun restBarScale(state: ShiftState): Float = if (state == ShiftState.LOCK) 1f else BAR_HIDDEN_SCALE
}

/**
 * Ressort amorti à raideur [k] et amortissement [c] réglables (ceux de [SpringValue] sont fixes), avec une vitesse de départ :
 * c'est ce qui permet le bond (vitesse initiale vers le haut) et les rebonds propres à chaque transition de l'icône Maj.
 */
internal class TunedSpring(var value: Float = 0f) {
    var target = value
        private set
    var velocity = 0f
        private set
    private var k = DEFAULT_K
    private var c = DEFAULT_C

    /** Vise [to] avec la raideur [stiffness] et l'amortissement [damping], à partir de la valeur courante et de la vitesse [startVelocity]. */
    fun go(to: Float, stiffness: Float, damping: Float, startVelocity: Float = 0f) {
        target = to
        k = stiffness
        c = damping
        velocity = startVelocity
    }

    /** Pose [v] sans animation et arrête le ressort (il reste là). */
    fun jump(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun step(dt: Float) {
        val n = ceil(dt / SUBSTEP_S).toInt().coerceAtLeast(1)
        val h = dt / n
        repeat(n) {
            velocity += (k * (target - value) - c * velocity) * h
            value += velocity * h
        }
        if (isSettled) jump(target)
    }

    val isSettled: Boolean get() = abs(target - value) < SETTLED_DISTANCE && abs(velocity) < SETTLED_SPEED

    private companion object {
        const val DEFAULT_K = 200f
        const val DEFAULT_C = 10f
        const val SUBSTEP_S = 0.004f
        const val SETTLED_DISTANCE = 0.002f
        const val SETTLED_SPEED = 0.01f
    }
}
