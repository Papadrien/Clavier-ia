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

    companion object {
        private const val PREFS_NAME = "analytics_prefs"
        private const val KEY_ENABLED = "analytics_enabled"
        const val DEFAULT_ENABLED = true
    }
}
