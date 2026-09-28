package fr.junade.taipo

import android.content.Context

/**
 * Paramètres du clavier modifiables depuis l'écran « Paramètres du clavier »
 * (KeyboardSettingsActivity) et lus par l'IME. Les futurs réglages du clavier
 * (retour haptique, hauteur...) viendront ici.
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

    companion object {
        private const val PREFS_NAME = "keyboard_prefs"
        private const val KEY_NUMBER_ROW = "number_row_enabled"
        const val DEFAULT_NUMBER_ROW = false
    }
}
