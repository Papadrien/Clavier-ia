package fr.junade.taipo.review

import android.content.Context

/** Compteur de lancements de l'application, pour le calendrier de [ReviewPromptPolicy]. */
class ReviewPromptPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Compte un lancement de plus et renvoie le nouveau total (1 au premier lancement), ou null une fois le calendrier
     * terminé ([ReviewPromptPolicy.isFinished]) : le compteur reste alors figé, plus rien n'est écrit.
     */
    fun recordLaunch(): Int? {
        val current = prefs.getInt(KEY_LAUNCH_COUNT, 0)
        if (ReviewPromptPolicy.isFinished(current)) return null
        val count = current + 1
        prefs.edit().putInt(KEY_LAUNCH_COUNT, count).apply()
        return count
    }

    companion object {
        const val PREFS_NAME = "review_prompt"
        const val KEY_LAUNCH_COUNT = "launch_count"
    }
}
