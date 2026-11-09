package fr.junade.taipo

import android.content.ComponentCallbacks2

/**
 * Revue de code (mémoire) : quand libérer les gros moteurs du clavier (modèle IA de plusieurs centaines de Mo à plusieurs Go,
 * modèle de dictée). Les dictionnaires restent : ils servent à chaque touche et se rechargent lentement.
 *
 * - Android manque sérieusement de mémoire (`TRIM_MEMORY_RUNNING_CRITICAL`, ou processus en arrière-plan) : toujours.
 * - Mémoire basse (`TRIM_MEMORY_RUNNING_LOW`) : seulement si le clavier est masqué, pour ne pas faire attendre une action en cours.
 * - Clavier masqué depuis [IDLE_RELEASE_MS] : libérés aussi, sans attendre qu'Android le demande.
 *
 * Logique pure, testée en JVM (`MemoryReleaseTest`).
 */
object MemoryRelease {

    /** Délai après lequel un clavier resté masqué libère ses moteurs (le modèle se recharge à la prochaine action IA). */
    const val IDLE_RELEASE_MS = 5 * 60 * 1000L

    @Suppress("DEPRECATION") // niveaux encore envoyés par Android, même s'ils sont marqués dépréciés depuis l'API 34
    fun shouldRelease(level: Int, keyboardShown: Boolean): Boolean = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> true
        level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false // écrans de l'application masqués : sans rapport
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> true
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> !keyboardShown
        else -> false
    }
}
