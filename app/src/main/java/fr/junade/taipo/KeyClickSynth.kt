package fr.junade.taipo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Une résonance du « boîtier » : sinusoïde amortie de [freqHz], qui s'éteint avec la constante de temps [decayMs]. */
data class ClickMode(val freqHz: Double, val decayMs: Double, val gain: Double)

/** Bruit d'impact : bruit blanc filtré (passe-haut [highPassHz], puis passe-bas [lowPassHz]), amorti en [decayMs]. */
data class ClickNoise(val highPassHz: Double, val lowPassHz: Double, val decayMs: Double, val gain: Double)

/**
 * Profil d'un son de touche synthétisé : quelques résonances amorties plus une impulsion de bruit, une courte montée
 * ([attackMs]) et une courte descente ([releaseMs]) pour éviter les clics parasites, un filtre passe-bas final et une
 * saturation douce ([drive]). Le son ne dure que [lenMs] : un seul « tic », sans temps de relâchement.
 */
data class ClickProfile(
    val lenMs: Double,
    val attackMs: Double,
    val releaseMs: Double,
    val modes: List<ClickMode>,
    val noise: ClickNoise,
    val lowPassHz: Double,
    val drive: Double,
) {
    /** Même son décalé en hauteur : fréquences des résonances et de tous les filtres multipliées par [factor]. */
    fun scaled(factor: Double) = copy(
        modes = modes.map { it.copy(freqHz = it.freqHz * factor) },
        noise = noise.copy(highPassHz = noise.highPassHz * factor, lowPassHz = noise.lowPassHz * factor),
        lowPassHz = lowPassHz * factor,
    )
}

/** Profils retenus pour le clavier (maquettes « sec doux » I1 et I2 du 19/10/2026, 5 ms ; touches de commande × 0,62). */
object KeyClickProfiles {
    private val SEC_DOUX = ClickProfile(
        lenMs = 5.0,
        attackMs = 0.4,
        releaseMs = 1.8,
        modes = listOf(ClickMode(180.0, 1.8, 0.5), ClickMode(1500.0, 1.2, 0.5), ClickMode(2900.0, 0.8, 0.25)),
        noise = ClickNoise(highPassHz = 1200.0, lowPassHz = 4500.0, decayMs = 1.2, gain = 0.6),
        lowPassHz = 4800.0,
        drive = 1.3,
    )

    /** Lettres, chiffres et symboles : « sec doux grave » (I1, filtres et résonances × 0,7). */
    val KEY = SEC_DOUX.scaled(0.7)

    /** Barre d'espace : « sec doux aigu » (I2, filtres et résonances × 1,35). */
    val SPACE = SEC_DOUX.scaled(1.35)

    /** Touches de commande (Maj, Effacer, Entrée, bascules, emoji) : « sec doux » × 0,62, 5 ms, un peu plus grave que les lettres. */
    val CONTROL = SEC_DOUX.scaled(0.62)
}

/** Son joué pour une touche : [KEY] pour les caractères, [SPACE] pour la barre d'espace, [CONTROL] pour les autres touches. */
enum class KeySound(val profile: ClickProfile) {
    KEY(KeyClickProfiles.KEY),
    SPACE(KeyClickProfiles.SPACE),
    CONTROL(KeyClickProfiles.CONTROL),
    ;

    companion object {
        /** Son d'une touche d'après son action : chaque touche du clavier en a un. */
        fun forAction(action: KeyAction): KeySound = when (action) {
            is KeyAction.TypeChar -> KEY
            KeyAction.Space -> SPACE
            KeyAction.Shift,
            KeyAction.Backspace,
            KeyAction.Enter,
            KeyAction.ToggleLayout,
            KeyAction.SymbolPage,
            KeyAction.Emoji,
            -> CONTROL
        }
    }
}

/**
 * Synthèse procédurale d'un « tic » de touche (Kotlin pur, sans Android : testable sur JVM). Reproduit à l'identique, à
 * l'arrondi près, le script de référence `tools/key_click_reference.py`. Le bruit vient d'un générateur congruentiel à
 * graine : une même graine redonne exactement le même son.
 */
object KeyClickSynth {
    const val SAMPLE_RATE = 44100
    private const val PEAK = 0.7

    /** Génère les échantillons PCM 16 bits mono de [profile] ; [variation] décale légèrement la hauteur selon la [seed]. */
    fun render(profile: ClickProfile, seed: Long, variation: Double = 0.03): ShortArray {
        val rate = SAMPLE_RATE.toDouble()
        val n = (rate * profile.lenMs / 1000.0).toInt()
        val rng = Lcg(seed)
        val detune = 1.0 + variation * rng.next()
        val volume = 1.0 + 0.08 * rng.next()
        val out = DoubleArray(n)

        for (mode in profile.modes) {
            val freq = mode.freqHz * detune
            var accumulated = 0.0
            for (i in 0 until n) {
                accumulated += freq
                val t = i / rate
                out[i] += mode.gain * exp(-t / (mode.decayMs / 1000.0)) * sin(2.0 * PI * accumulated / rate)
            }
        }

        val noise = DoubleArray(n) { rng.next() }
        val shaped = onePole(onePole(noise, profile.noise.highPassHz, highPass = true), profile.noise.lowPassHz, highPass = false)
        for (i in 0 until n) {
            val t = i / rate
            out[i] += shaped[i] * exp(-t / (profile.noise.decayMs / 1000.0)) * profile.noise.gain * 3.0
        }

        val attack = max(1, (rate * profile.attackMs / 1000.0).toInt())
        if (attack > 1) for (i in 0 until attack) out[i] *= i / (attack - 1.0)
        val release = (rate * profile.releaseMs / 1000.0).toInt()
        if (release > 1) for (j in 0 until release) out[n - release + j] *= 1.0 - j / (release - 1.0)

        val filtered = onePole(out, profile.lowPassHz, highPass = false)
        var peak = 0.0
        for (i in 0 until n) {
            filtered[i] = tanh(filtered[i] * profile.drive) * volume
            peak = max(peak, abs(filtered[i]))
        }
        val scale = if (peak > 0.0) PEAK / peak else 0.0
        return ShortArray(n) { (filtered[it] * scale * 32767.0).roundToInt().toShort() }
    }

    /** Fichier WAV (PCM 16 bits mono) contenant [samples] : le format que lit `SoundPool`. */
    fun toWav(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataSize = samples.size * 2
        val bytes = ByteArray(44 + dataSize)
        fun putInt(offset: Int, value: Int) {
            for (k in 0 until 4) bytes[offset + k] = (value shr (8 * k)).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value shr 8).toByte()
        }
        "RIFF".forEachIndexed { k, c -> bytes[k] = c.code.toByte() }
        putInt(4, 36 + dataSize)
        "WAVEfmt ".forEachIndexed { k, c -> bytes[8 + k] = c.code.toByte() }
        putInt(16, 16)
        putShort(20, 1) // PCM
        putShort(22, 1) // mono
        putInt(24, sampleRate)
        putInt(28, sampleRate * 2)
        putShort(32, 2)
        putShort(34, 16)
        "data".forEachIndexed { k, c -> bytes[36 + k] = c.code.toByte() }
        putInt(40, dataSize)
        samples.forEachIndexed { i, s -> putShort(44 + i * 2, s.toInt()) }
        return bytes
    }

    private fun onePole(input: DoubleArray, cutoffHz: Double, highPass: Boolean): DoubleArray {
        val a = exp(-2.0 * PI * cutoffHz / SAMPLE_RATE)
        val out = DoubleArray(input.size)
        var z = 0.0
        for (i in input.indices) {
            z = (1.0 - a) * input[i] + a * z
            out[i] = if (highPass) input[i] - z else z
        }
        return out
    }

    /** Générateur congruentiel linéaire 32 bits : suite de valeurs dans [-1 ; 1[ identique d'une plateforme à l'autre. */
    private class Lcg(seed: Long) {
        private var state = seed and 0xFFFFFFFFL

        fun next(): Double {
            state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return state / 2147483648.0 - 1.0
        }
    }
}
