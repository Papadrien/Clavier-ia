package fr.junade.taipo

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Story 1.11 : niveaux de retour haptique réglables depuis les paramètres du clavier. « Moyen »
 * est le niveau par défaut. Chaque niveau a sa propre durée et amplitude de vibration ; [OFF]
 * désactive complètement le retour haptique. Impulsion très brève à amplitude fixe (effet « clic »).
 */
enum class HapticIntensity(val storageKey: String, private val durationMs: Long, private val amplitude: Int) {
    OFF("off", 0L, 0),
    LIGHT("light", 4L, 40),
    MEDIUM("medium", 6L, 100),
    STRONG("strong", 10L, 220),
    ;

    /** Effet de vibration correspondant, ou null si le retour haptique est désactivé. */
    fun toVibrationEffect(): VibrationEffect? =
        if (this == OFF) null else VibrationEffect.createOneShot(durationMs, amplitude)

    companion object {
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
