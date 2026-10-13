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
     * Langue du clavier choisie dans les paramètres (disposition, accents, dictionnaire, autocorrection) ; null =
     * automatique : langue du système si le clavier la gère, sinon français ([KeyboardLanguageChoice]).
     * Relue à chaque ouverture d'un champ.
     */
    var keyboardLanguage: KeyboardLanguage?
        get() = KeyboardLanguage.fromTag(prefs.getString(KEY_LANGUAGE, null))
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, value.tag)
            }.apply()
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

    /**
     * « Apprendre de mes habitudes » : le clavier compte ce que l'utilisateur fait des corrections automatiques
     * (annulée, restée en place) et des mots qu'il valide, pour s'y adapter (apprentissage des habitudes de
     * correction). Activé par défaut, comme la prédiction du mot suivant. Désactivé, plus rien n'est compté ;
     * ce qui l'a déjà été reste jusqu'à l'effacement depuis l'écran des paramètres.
     * Lu par SuggestionController à chaque nouveau champ.
     */
    var isHabitLearningEnabled: Boolean
        get() = prefs.getBoolean(KEY_HABIT_LEARNING, DEFAULT_HABIT_LEARNING)
        set(value) {
            prefs.edit().putBoolean(KEY_HABIT_LEARNING, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "keyboard_prefs"
        private const val KEY_NUMBER_ROW = "number_row_enabled"
        const val DEFAULT_NUMBER_ROW = false
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"
        private const val KEY_LANGUAGE = "keyboard_language"
        private const val KEY_RECENT_EMOJIS = "recent_emojis"
        private const val KEY_HABIT_LEARNING = "habit_learning_enabled"
        const val DEFAULT_HABIT_LEARNING = true
    }
}
