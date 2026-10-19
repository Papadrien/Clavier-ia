package fr.junade.taipo

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Story 1.11 : niveaux de retour haptique réglables depuis les paramètres du clavier. « Moyen »
 * est le niveau par défaut. Les niveaux se distinguent par l'amplitude seule : la durée est la même
 * ([CLICK_DURATION_MS]) pour tous ; [OFF] désactive complètement le retour haptique. Impulsion très
 * brève à amplitude fixe (effet « clic »).
 */
enum class HapticIntensity(val storageKey: String, val amplitude: Int) {
    OFF("off", 0),
    LIGHT("light", 40),
    MEDIUM("medium", 100),
    STRONG("strong", 220),
    ;

    /** Effet de vibration correspondant, ou null si le retour haptique est désactivé. */
    fun toVibrationEffect(): VibrationEffect? =
        if (this == OFF) null else VibrationEffect.createOneShot(CLICK_DURATION_MS, amplitude)

    /**
     * Niveau utilisé quand on déplace le curseur en glissant sur la barre espace : toujours faible,
     * quel que soit le niveau choisi, et absent quand le retour haptique est désactivé.
     */
    fun cursorMoveFeedback(): HapticIntensity = if (this == OFF) OFF else LIGHT

    companion object {
        /** Durée de l'impulsion, identique pour tous les niveaux (l'ancien niveau fort durait 10 ms, puis 9, 8 et 7 ms). */
        const val CLICK_DURATION_MS = 6L

        val DEFAULT = MEDIUM

        fun fromStorageKey(key: String?): HapticIntensity = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/** Une impulsion d'une séquence : [delayMs] de silence avant, puis [durationMs] de vibration à l'[amplitude] donnée (1 à 255). */
data class HapticPulse(val delayMs: Long, val durationMs: Long, val amplitude: Int)

/**
 * Retours haptiques des actions IA (saisie vocale, transcription IA, correction, mode chat). « Fort », « moyen » et « faible »
 * reprennent les amplitudes des niveaux du clavier ([HapticIntensity.STRONG], [HapticIntensity.MEDIUM], [HapticIntensity.LIGHT]), quel que soit le niveau choisi
 * dans les paramètres ; seul [PROCESSING_DONE] (fin de transcription ou de correction) suit ce niveau (comme une frappe de touche). Si les vibrations sont
 * désactivées dans les paramètres ([HapticIntensity.OFF]), aucune de ces vibrations n'est jouée.
 */
enum class AiHapticCue {
    /** Premier appui sur une action IA (déclenche le chargement ou l'enregistrement) : 7 ms, fort. */
    ACTION_START,

    /** Fin du chargement du modèle vocal, le micro capte : 6 ms, faible. */
    LOADING_DONE,

    /** Fin de la saisie vocale (appui, ou arrêt automatique après silence ou micro perdu) : 6 ms, fort. */
    RECORDING_STOP,

    /** Appui qui termine l'enregistrement de la transcription IA : deux impulsions de 6 ms, fortes. */
    TRANSCRIPTION_STOP,

    /** Fin du traitement de la transcription IA ou de la correction IA : comme une frappe de touche (6 ms, niveau des paramètres). */
    PROCESSING_DONE,

    /** Mode chat : un mot de la réponse vient d'être généré : 6 ms, faible. */
    CHAT_WORD,

    /** Mode chat : fin de la génération de la réponse : 6 ms, moyen. */
    CHAT_DONE,
    ;

    /** Impulsions à jouer pour ce signal avec le niveau [setting] des paramètres ; vide si les vibrations sont désactivées. */
    fun pulses(setting: HapticIntensity): List<HapticPulse> {
        if (setting == HapticIntensity.OFF) return emptyList()
        val strong = HapticIntensity.STRONG.amplitude
        return when (this) {
            ACTION_START -> listOf(HapticPulse(0, START_DURATION_MS, strong))
            LOADING_DONE -> listOf(HapticPulse(0, HapticIntensity.CLICK_DURATION_MS, HapticIntensity.LIGHT.amplitude))
            RECORDING_STOP -> listOf(HapticPulse(0, HapticIntensity.CLICK_DURATION_MS, strong))
            TRANSCRIPTION_STOP -> listOf(
                HapticPulse(0, HapticIntensity.CLICK_DURATION_MS, strong),
                HapticPulse(DOUBLE_GAP_MS, HapticIntensity.CLICK_DURATION_MS, strong),
            )
            PROCESSING_DONE -> listOf(HapticPulse(0, HapticIntensity.CLICK_DURATION_MS, setting.amplitude))
            CHAT_WORD -> listOf(HapticPulse(0, HapticIntensity.CLICK_DURATION_MS, HapticIntensity.LIGHT.amplitude))
            CHAT_DONE -> listOf(HapticPulse(0, HapticIntensity.CLICK_DURATION_MS, HapticIntensity.MEDIUM.amplitude))
        }
    }

    companion object {
        /** Durée de l'impulsion du premier appui sur une action IA. */
        const val START_DURATION_MS = 7L

        /** Silence entre les deux impulsions de [TRANSCRIPTION_STOP]. */
        const val DOUBLE_GAP_MS = 70L
    }
}

/**
 * Déclenche la vibration correspondant au niveau choisi dans les paramètres (story 1.11), à chaque
 * frappe de touche. Sans vibreur disponible sur l'appareil, [perform] ne fait simplement rien.
 */
class HapticFeedbackPlayer(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun perform(intensity: HapticIntensity) {
        val effect = intensity.toVibrationEffect() ?: return
        vibrator?.vibrate(effect)
    }

    /** Joue le signal [cue] des actions IA ; [setting] est le niveau choisi dans les paramètres du clavier. */
    fun perform(cue: AiHapticCue, setting: HapticIntensity) {
        val pulses = cue.pulses(setting)
        if (pulses.isEmpty()) return
        val effect = if (pulses.size == 1 && pulses[0].delayMs == 0L) {
            VibrationEffect.createOneShot(pulses[0].durationMs, pulses[0].amplitude)
        } else {
            val timings = ArrayList<Long>()
            val amplitudes = ArrayList<Int>()
            pulses.forEach {
                timings.add(it.delayMs)
                amplitudes.add(0)
                timings.add(it.durationMs)
                amplitudes.add(it.amplitude)
            }
            VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
        }
        vibrator?.vibrate(effect)
    }
}
