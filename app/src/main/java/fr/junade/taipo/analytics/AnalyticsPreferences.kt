package fr.junade.taipo.analytics

import android.content.Context

/**
 * Réglage « Mesure d'usage anonyme » (Paramètres du clavier). Activée par défaut, désactivable à tout moment : une fois
 * désactivée, plus aucun événement n'est créé ni envoyé, et ceux en attente sont abandonnés.
 */
class AnalyticsPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /**
     * Vrai une fois la première ouverture traitée ([FirstOpenPolicy]) : événement envoyé, ou ignoré parce que l'application
     * a été mise à jour. Ce réglage suit la sauvegarde automatique d'Android : une réinstallation avec restauration ou un
     * changement de téléphone ne recompte donc pas le même utilisateur.
     */
    var firstOpenHandled: Boolean
        get() = prefs.getBoolean(KEY_FIRST_OPEN_HANDLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FIRST_OPEN_HANDLED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "analytics_prefs"
        private const val KEY_ENABLED = "analytics_enabled"
        private const val KEY_FIRST_OPEN_HANDLED = "first_open_handled"
        const val DEFAULT_ENABLED = true
    }
}
