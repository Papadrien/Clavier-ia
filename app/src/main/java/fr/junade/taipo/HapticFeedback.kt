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
enum class HapticIntensity(val storageKey: String, private val amplitude: Int) {
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
        /** Durée de l'impulsion, identique pour tous les niveaux (l'ancien niveau fort durait 10 ms, puis 9 ms, puis 8 ms). */
        const val CLICK_DURATION_MS = 7L

        val DEFAULT = MEDIUM

        fun fromStorageKey(key: String?): HapticIntensity = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
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
}
