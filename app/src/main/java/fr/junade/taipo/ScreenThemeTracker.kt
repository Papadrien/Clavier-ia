package fr.junade.taipo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.WeakHashMap

/**
 * Les écrans suivent le thème du clavier ([KeyboardTheme.wrap]). Un écran resté dans la pile pendant un changement de thème
 * (page « Apparence », ou thème du système avec le choix « Système ») se recrée quand on y revient, pour ne pas garder
 * l'ancien thème. Un seul point d'entrée, pour toutes les activités de l'application.
 */
object ScreenThemeTracker : Application.ActivityLifecycleCallbacks {

    private val looks = WeakHashMap<Activity, Pair<KeyboardTheme.Mode, Boolean>>()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        looks[activity] = KeyboardTheme.screenLook()
    }

    override fun onActivityResumed(activity: Activity) {
        val created = looks[activity] ?: return
        if (KeyboardTheme.needsRecreate(created)) activity.recreate()
    }

    override fun onActivityDestroyed(activity: Activity) {
        looks.remove(activity)
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
