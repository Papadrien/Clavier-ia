package fr.junade.taipo

import android.app.Application
import fr.junade.taipo.analytics.Analytics

/**
 * Point d'entrée du processus (clavier, écrans et téléchargements y partagent le même) : initialise la mesure d'usage
 * anonyme, voir `docs/analytics.md`, et relit le thème du clavier.
 */
class TaipoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        KeyboardTheme.choice = KeyboardPreferences(this).themeChoice // avant toute vue du clavier
        Analytics.initialize(this)
    }
}
