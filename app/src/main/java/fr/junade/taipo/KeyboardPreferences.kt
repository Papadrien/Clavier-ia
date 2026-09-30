package fr.junade.taipo

import android.content.Context
import fr.junade.taipo.emoji.RecentEmojis

/**
 * Paramètres du clavier modifiables depuis l'écran « Paramètres du clavier »
 * (KeyboardSettingsActivity) et lus par l'IME. Les futurs réglages du clavier
 * (hauteur du clavier : story 1.12...) sont regroupés ici.
 *
 * Le clavier relit ces valeurs à chaque ouverture d'un champ de saisie : un
 * réglage modifié dans l'écran est donc pris en compte dès le prochain champ
 * (le clavier est de toute façon masqué pendant qu'on est dans les paramètres).
 */
class KeyboardPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Rangée de chiffres au-dessus des lettres (story 1.5). Désactivée par défaut, comme sur Gboard. */
    var isNumberRowEnabled: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, DEFAULT_NUMBER_ROW)
        set(value) {
            prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()
        }

    /** Retour haptique au toucher des touches (story 1.11). Moyen par défaut. */
    var hapticIntensity: HapticIntensity
        get() = HapticIntensity.fromStorageKey(prefs.getString(KEY_HAPTIC_INTENSITY, null))
        set(value) {
            prefs.edit().putString(KEY_HAPTIC_INTENSITY, value.storageKey).apply()
        }

    /** Hauteur du clavier (story 1.12). Normale (100 %) par défaut. */
    var keyboardHeight: KeyboardHeight
        get() = KeyboardHeight.fromStorageKey(prefs.getString(KEY_KEYBOARD_HEIGHT, null))
        set(value) {
            prefs.edit().putString(KEY_KEYBOARD_HEIGHT, value.storageKey).apply()
        }

    /**
     * Emojis récemment utilisés (story 1.15), du plus récent au plus ancien. Stockés localement,
     * comme les autres réglages : juste la liste des derniers emojis choisis, rien n'est analysé.
     */
    var recentEmojis: List<String>
        get() = RecentEmojis.decode(prefs.getString(KEY_RECENT_EMOJIS, null))
        set(value) {
            prefs.edit().putString(KEY_RECENT_EMOJIS, RecentEmojis.encode(value)).apply()
        }

    companion object {
        private const val PREFS_NAME = "keyboard_prefs"
        private const val KEY_NUMBER_ROW = "number_row_enabled"
        const val DEFAULT_NUMBER_ROW = false
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"
        private const val KEY_RECENT_EMOJIS = "recent_emojis"
    }
}
