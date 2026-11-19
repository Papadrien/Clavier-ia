package fr.junade.taipo.backdrop

import java.util.Random

/**
 * Frappe simulée du fond animé pendant une action IA (correction, réponse du chat, transcription) : le motif réagit comme si
 * l'utilisateur tapait, au rythme d'une frappe ordinaire (une touche toutes les [MIN_INTERVAL_S] à [MAX_INTERVAL_S] secondes),
 * à des endroits tirés au hasard sur les touches. Logique pure, testée en JVM (`AmbientTypingTest`).
 */
class AmbientTyping(seed: Long = 1L) {

    private val random = Random(seed)
    private var untilNext = 0f

    /** Nouvelle action IA : la première touche simulée tombe tout de suite. */
    fun reset() {
        untilNext = 0f
    }

    /**
     * Avance de [dtSeconds] et appelle [onPress] pour chaque touche simulée due, avec sa position en fractions (0 à 1) de la
     * largeur et de la hauteur de la zone des touches. Au plus [MAX_PRESSES_PER_STEP] par appel : une trame en retard ne
     * déclenche pas une rafale. Renvoie le nombre de touches simulées.
     */
    fun advance(dtSeconds: Float, onPress: (Float, Float) -> Unit): Int {
        if (dtSeconds < 0f) return 0
        untilNext -= dtSeconds
        var count = 0
        while (untilNext <= 0f && count < MAX_PRESSES_PER_STEP) {
            onPress(random.nextFloat(), random.nextFloat())
            count++
            untilNext += MIN_INTERVAL_S + random.nextFloat() * (MAX_INTERVAL_S - MIN_INTERVAL_S)
        }
        if (untilNext < 0f) untilNext = 0f
        return count
    }

    companion object {
        /** Intervalle entre deux touches simulées : environ 4 à 8 touches par seconde, une frappe soutenue. */
        const val MIN_INTERVAL_S = 0.12f
        const val MAX_INTERVAL_S = 0.26f
        const val MAX_PRESSES_PER_STEP = 2
    }
}
