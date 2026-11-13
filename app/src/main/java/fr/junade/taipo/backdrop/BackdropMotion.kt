package fr.junade.taipo.backdrop

import kotlin.math.exp
import kotlin.math.min

/**
 * Mouvement d'un fond animé, piloté par la frappe (logique pure, testée en JVM) :
 * - [phase] n'avance que pendant la frappe : chaque touche fait dériver le motif (bulles en orbite, facettes qui glissent,
 *   rubans qui tournent, ondes qui s'écoulent), qui s'arrête là où il est arrivé ;
 * - [energy] : élan donné par les touches, qui retombe en moins d'une seconde ;
 * - [hits] : touches récentes, pour les effets locaux (petite bulle, onde de lumière), oubliées au bout de [HIT_LIFETIME_S].
 * À l'arrêt ([isRunning] faux), rien n'est redessiné.
 */
class BackdropMotion {

    class Hit(val x: Float, val y: Float) {
        var age = 0f
            internal set
    }

    var phase = 0f
        private set
    var energy = 0f
        private set
    val hits: List<Hit> get() = activeHits
    private val activeHits = ArrayList<Hit>()

    val isRunning: Boolean get() = energy > SETTLE_ENERGY || activeHits.isNotEmpty()

    /** Une touche a été appuyée en ([x], [y]) (coordonnées du fond). */
    fun press(x: Float, y: Float) {
        activeHits += Hit(x, y)
        if (activeHits.size > MAX_HITS) activeHits.removeAt(0)
        energy = min(MAX_ENERGY, energy + ENERGY_PER_KEY)
    }

    fun step(dtSeconds: Float) {
        if (dtSeconds <= 0f || !isRunning) return
        phase += dtSeconds * (IDLE_SPEED + DRIFT_SPEED * energy)
        energy *= exp(-dtSeconds * ENERGY_DECAY)
        if (energy <= SETTLE_ENERGY) energy = 0f
        activeHits.forEach { it.age += dtSeconds }
        activeHits.removeAll { it.age >= HIT_LIFETIME_S }
    }

    /** Pose une phase (aperçus de la page « Thème », animés au défilement). */
    fun setPhase(value: Float) {
        phase = value
    }

    companion object {
        const val ENERGY_PER_KEY = 0.55f
        const val MAX_ENERGY = 1.6f
        const val ENERGY_DECAY = 2.6f
        const val IDLE_SPEED = 0.6f
        const val DRIFT_SPEED = 4.5f
        const val HIT_LIFETIME_S = 1.4f
        const val SETTLE_ENERGY = 0.01f
        const val MAX_HITS = 8
    }
}
