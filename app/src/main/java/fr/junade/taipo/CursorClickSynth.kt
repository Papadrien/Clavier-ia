package fr.junade.taipo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Son du déplacement du curseur au glissement sur la barre d'espace, « goutte » (proposition D du 21/10/2026) : une
 * petite montée douce de 6 ms (de 450 vers 900 Hz, avec une harmonique très légère), l'inverse de la descente de la
 * gomme. Pic à 0,30 (contre 0,7 pour les touches) : un glissement enchaîne les crans, le son doit rester discret.
 * Reproduit, à l'arrondi près, `render_cursor` de `tools/key_click_reference.py`.
 *
 * Kotlin pur (sans Android, testable sur JVM). La graine ne décale que la hauteur ([variation]) : le son n'a pas de bruit.
 */
object CursorClickSynth {
    private const val LEN_MS = 6.0
    private const val START_HZ = 450.0
    private const val END_HZ = 900.0
    private const val GLIDE_DECAY_S = 0.002
    private const val BODY_DECAY_S = 0.002
    private const val ATTACK_MS = 0.8
    private const val RELEASE_MS = 2.5
    private const val LOW_PASS_HZ = 3500.0
    private const val DRIVE = 1.1
    private const val PEAK = 0.30

    /** Génère les échantillons PCM 16 bits mono ; [variation] décale légèrement la hauteur selon la [seed]. */
    fun render(seed: Long, variation: Double = 0.03): ShortArray {
        val rate = KeyClickSynth.SAMPLE_RATE.toDouble()
        val n = (rate * LEN_MS / 1000.0).toInt()
        val rng = KeyClickSynth.Lcg(seed)
        val detune = 1.0 + variation * rng.next()
        val out = DoubleArray(n)

        var accumulated = 0.0
        for (i in 0 until n) {
            val t = i / rate
            accumulated += (START_HZ + (END_HZ - START_HZ) * (1.0 - exp(-t / GLIDE_DECAY_S))) * detune
            val phase = 2.0 * PI * accumulated / rate
            out[i] = exp(-t / BODY_DECAY_S) * (sin(phase) * 0.7 + sin(2.0 * phase) * 0.1)
        }

        val attack = max(1, (rate * ATTACK_MS / 1000.0).toInt())
        if (attack > 1) for (i in 0 until attack) out[i] *= i / (attack - 1.0)
        val release = (rate * RELEASE_MS / 1000.0).toInt()
        if (release > 1) for (j in 0 until release) out[n - release + j] *= 1.0 - j / (release - 1.0)

        val filtered = KeyClickSynth.onePole(out, LOW_PASS_HZ, highPass = false)
        var peak = 0.0
        for (i in 0 until n) {
            filtered[i] = tanh(filtered[i] * DRIVE)
            peak = max(peak, abs(filtered[i]))
        }
        val scale = if (peak > 0.0) PEAK / peak else 0.0
        return ShortArray(n) { (filtered[it] * scale * 32767.0).roundToInt().toShort() }
    }
}
