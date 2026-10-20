package fr.junade.taipo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Un clic de bascule : [startMs] depuis le début du son, [gain] relatif et [pitchFactor] appliqué à la hauteur de base. */
data class SwitchClick(val startMs: Double, val gain: Double, val pitchFactor: Double)

/**
 * Sons « interrupteur » de la barre du haut : une suite de clics de bascule. Chaque clic est fait de deux résonances
 * amorties et d'une impulsion de bruit filtré (passe-haut). Deux réglages, tous deux issus des maquettes du 19/10/2026 :
 * - [BAR] : Paramètres, Smart Clipboard et Annuler, double clic de 32 ms (appui, puis retour 14 ms plus tard, plus grave
 *   et plus faible), hauteur × 1,25 (« Interrupteur », médium, version Smart Clipboard) ;
 * - [AI] : robot, Chat, Corriger et Transcription IA, « triple montée » grave de 48 ms (trois clics à 0, 11 et 22 ms,
 *   de plus en plus aigus et faibles), hauteur × 1,0 ;
 * - [VOICE_START] et [VOICE_END] : début et fin de la saisie vocale, deux clics espacés de 70 ms, hauteur × 1,25 (maquette
 *   « saisie vocale » du 20/10/2026, médium) : le début monte (second clic plus aigu), la fin descend (retour plus grave
 *   et plus faible) ;
 * - [TRANSCRIPTION_START], [TRANSCRIPTION_LISTEN_END] et [TRANSCRIPTION_DONE] : démarrage de l'écoute, fin d'écoute et fin
 *   de traitement de la transcription IA, triples clics « rapprochés d'abord » (tic-tic puis tac, 16 ms puis 60 ms),
 *   hauteur × 1,0 (maquette « transcription IA » du 20/10/2026, grave) : le démarrage et la fin de traitement montent
 *   (le dernier clic de la fin de traitement est le plus aigu), la fin d'écoute descend.
 *
 * Kotlin pur (sans Android, testable sur JVM) : reproduit, à l'arrondi près, `render_clicks` de
 * `tools/key_click_reference.py`. Le bruit vient du même générateur congruentiel à graine que [KeyClickSynth].
 */
object SwitchClickSynth {
    /** Réglage d'un son : suite de [clicks], durée totale [lenMs] et hauteur de base [pitch]. */
    data class Preset(val clicks: List<SwitchClick>, val lenMs: Double, val pitch: Double)

    val BAR = Preset(
        clicks = listOf(SwitchClick(0.0, 1.0, 1.0), SwitchClick(14.0, 0.6, 0.8)),
        lenMs = 32.0,
        pitch = 1.25,
    )

    val AI = Preset(
        clicks = listOf(SwitchClick(0.0, 1.0, 1.0), SwitchClick(11.0, 0.8, 1.2), SwitchClick(22.0, 0.65, 1.45)),
        lenMs = 48.0,
        pitch = 1.0,
    )

    val VOICE_START = Preset(
        clicks = listOf(SwitchClick(0.0, 1.0, 1.0), SwitchClick(70.0, 0.85, 1.25)),
        lenMs = 90.0,
        pitch = 1.25,
    )

    val VOICE_END = Preset(
        clicks = listOf(SwitchClick(0.0, 1.0, 1.25), SwitchClick(70.0, 0.6, 0.8)),
        lenMs = 90.0,
        pitch = 1.25,
    )

    val TRANSCRIPTION_START = Preset(
        clicks = listOf(SwitchClick(0.0, 1.0, 1.0), SwitchClick(16.0, 0.8, 1.15), SwitchClick(76.0, 0.9, 1.4)),
        lenMs = 96.0,
        pitch = 1.0,
    )

    val TRANSCRIPTION_LISTEN_END = Preset(
        clicks = listOf(SwitchClick(0.0, 1.0, 1.3), SwitchClick(16.0, 0.7, 1.15), SwitchClick(76.0, 0.6, 0.8)),
        lenMs = 96.0,
        pitch = 1.0,
    )

    val TRANSCRIPTION_DONE = Preset(
        clicks = listOf(SwitchClick(0.0, 1.0, 1.0), SwitchClick(16.0, 0.8, 1.2), SwitchClick(76.0, 0.95, 1.6)),
        lenMs = 96.0,
        pitch = 1.0,
    )

    private const val ATTACK_MS = 0.4
    private const val RELEASE_MS = 4.0
    private const val LOW_PASS_HZ = 7000.0
    private const val DRIVE = 1.2
    private const val PEAK = 0.6

    /** Génère les échantillons PCM 16 bits mono de [preset] ; [variation] décale légèrement la hauteur selon la [seed]. */
    fun render(preset: Preset, seed: Long, variation: Double = 0.03): ShortArray {
        val rate = KeyClickSynth.SAMPLE_RATE.toDouble()
        val n = (rate * preset.lenMs / 1000.0).toInt()
        val rng = KeyClickSynth.Lcg(seed)
        val pitch = preset.pitch * (1.0 + variation * rng.next())
        val out = DoubleArray(n)

        for (click in preset.clicks) {
            val factor = pitch * click.pitchFactor
            val start = (rate * click.startMs / 1000.0).toInt()
            val m = n - start
            val noise = DoubleArray(m) { rng.next() }
            val shaped = KeyClickSynth.onePole(noise, 1500.0 * factor, highPass = true)
            for (j in 0 until m) {
                val t = j / rate
                val body = click.gain * exp(-t / 0.002) * sin(2.0 * PI * 1200.0 * factor * t) +
                    click.gain * 0.5 * exp(-t / 0.0012) * sin(2.0 * PI * 2600.0 * factor * t)
                out[start + j] += body + shaped[j] * exp(-t / 0.0012) * click.gain * 0.5
            }
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
