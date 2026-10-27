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

    /** Son du clavier (tic doux à chaque frappe, plus aigu pour la barre d'espace). Activé par défaut. */
    var isKeySoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEY_SOUND, DEFAULT_KEY_SOUND)
        set(value) {
            prefs.edit().putBoolean(KEY_KEY_SOUND, value).apply()
        }

    /**
     * Sons des actions de la barre supérieure (Paramètres, Smart Clipboard, Annuler, robot, Chat, Corriger, saisie
     * vocale, transcription IA). Activé par défaut ; tant qu'il n'a jamais été réglé, il reprend l'ancien réglage
     * unique « Son des touches », pour ne pas réactiver des sons qu'on avait coupés.
     */
    var isBarSoundEnabled: Boolean
        get() = if (prefs.contains(KEY_BAR_SOUND)) {
            prefs.getBoolean(KEY_BAR_SOUND, DEFAULT_BAR_SOUND)
        } else {
            prefs.getBoolean(KEY_KEY_SOUND, DEFAULT_BAR_SOUND)
        }
        set(value) {
            prefs.edit().putBoolean(KEY_BAR_SOUND, value).apply()
        }

    /**
     * Volume des sons du clavier et de la barre supérieure, en pourcentage (0 à 100, voir [KeySoundVolume]). 80 % par
     * défaut : le volume d'avant l'ajout du curseur. Relu à chaque ouverture de champ.
     */
    var keySoundVolumePercent: Int
        get() {
            migrateVolumeScale()
            return KeySoundVolume.clamp(prefs.getInt(KEY_SOUND_VOLUME, KeySoundVolume.DEFAULT_PERCENT))
        }
        set(value) {
            migrateVolumeScale()
            prefs.edit().putInt(KEY_SOUND_VOLUME, KeySoundVolume.clamp(value)).apply()
        }

    /**
     * Le maximum du curseur de volume a été divisé par deux (l'ancien 50 % est devenu 100 %) : une valeur enregistrée
     * avec l'ancienne échelle est doublée (plafonnée à 100) une seule fois, pour garder le même volume.
     */
    private fun migrateVolumeScale() {
        if (prefs.getBoolean(KEY_VOLUME_RESCALED, false)) return
        prefs.edit().apply {
            if (prefs.contains(KEY_SOUND_VOLUME)) {
                putInt(KEY_SOUND_VOLUME, KeySoundVolume.clamp(prefs.getInt(KEY_SOUND_VOLUME, 0) * 2))
            }
            putBoolean(KEY_VOLUME_RESCALED, true)
        }.apply()
    }

    /** Hauteur du clavier (story 1.12). Normale (100 %) par défaut. */
    var keyboardHeight: KeyboardHeight
        get() = KeyboardHeight.fromStorageKey(prefs.getString(KEY_KEYBOARD_HEIGHT, null))
        set(value) {
            prefs.edit().putString(KEY_KEYBOARD_HEIGHT, value.storageKey).apply()
        }

    /** Marge sous les touches en portrait, en dp ([KeyboardBottomMargin]). 60 dp par défaut. Relue à chaque ouverture de champ. */
    var bottomMarginDp: Int
        get() = KeyboardBottomMargin.clamp(prefs.getInt(KEY_BOTTOM_MARGIN, KeyboardBottomMargin.DEFAULT_DP))
        set(value) {
            prefs.edit().putInt(KEY_BOTTOM_MARGIN, KeyboardBottomMargin.clamp(value)).apply()
        }

    /**
     * Langue du clavier choisie dans les paramètres (disposition, accents, dictionnaire, autocorrection) ; null =
     * automatique : première langue du système gérée par le clavier, sinon anglais ([KeyboardLanguageChoice]).
     * Relue à chaque ouverture d'un champ.
     */
    var isMultilingualEnabled: Boolean
        get() = prefs.getBoolean(KEY_MULTILINGUAL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MULTILINGUAL, value).apply()
        }

    /** Langues cochées pour le clavier multilingue (dans l'ordre de [KeyboardLanguage.entries]). */
    var multilingualSelection: List<KeyboardLanguage>
        get() = MultilingualChoice.decode(prefs.getString(KEY_MULTILINGUAL_LANGUAGES, null))
        set(value) {
            prefs.edit().putString(KEY_MULTILINGUAL_LANGUAGES, MultilingualChoice.encode(value)).apply()
        }

    /** Langues du clavier multilingue effectivement utilisées : vide si le mode est désactivé ou si moins de deux sont cochées. */
    val multilingualLanguages: List<KeyboardLanguage>
        get() = if (isMultilingualEnabled) multilingualSelection.takeIf { MultilingualChoice.isActive(it) }.orEmpty() else emptyList()

    /** Dernière langue choisie par appui long sur Entrée (conservée entre les champs). */
    var activeMultilingualLanguage: KeyboardLanguage?
        get() = KeyboardLanguage.fromTag(prefs.getString(KEY_MULTILINGUAL_ACTIVE, null))
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_MULTILINGUAL_ACTIVE) else putString(KEY_MULTILINGUAL_ACTIVE, value.tag)
            }.apply()
        }

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
        private const val KEY_KEY_SOUND = "key_sound_enabled"
        const val DEFAULT_KEY_SOUND = true
        private const val KEY_SOUND_VOLUME = "key_sound_volume_percent"
        private const val KEY_VOLUME_RESCALED = "key_sound_volume_rescaled"
        private const val KEY_BAR_SOUND = "bar_sound_enabled"
        const val DEFAULT_BAR_SOUND = true
        private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"
        private const val KEY_BOTTOM_MARGIN = "bottom_margin_dp"
        private const val KEY_LANGUAGE = "keyboard_language"
        private const val KEY_MULTILINGUAL = "multilingual_enabled"
        private const val KEY_MULTILINGUAL_LANGUAGES = "multilingual_languages"
        private const val KEY_MULTILINGUAL_ACTIVE = "multilingual_active_language"
        private const val KEY_RECENT_EMOJIS = "recent_emojis"
        private const val KEY_HABIT_LEARNING = "habit_learning_enabled"
        const val DEFAULT_HABIT_LEARNING = true
    }
}
