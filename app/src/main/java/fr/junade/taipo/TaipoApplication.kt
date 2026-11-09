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
        val keyboard = KeyboardPreferences(this) // thème et couleur d'accent : avant toute vue du clavier
        KeyboardTheme.choice = keyboard.themeChoice
        PremiumPass.load(this) // pass premium (dernier état connu de Google Play) ; pose aussi la couleur d'accent du clavier
        Analytics.initialize(this)
    }
}
