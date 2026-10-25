package fr.junade.taipo

import android.app.Application
import fr.junade.taipo.analytics.Analytics

/**
 * Point d'entrée du processus (clavier, écrans et téléchargements y partagent le même) : initialise la mesure d'usage
 * anonyme, voir `docs/analytics.md`.
 */
class TaipoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Analytics.initialize(this)
    }
}
