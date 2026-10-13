package fr.junade.taipo.review

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import fr.junade.taipo.AppLog

/**
 * Demande d'avis intégrée (Google Play In-App Review) : note en étoiles et commentaire dans une fenêtre en bas de
 * l'écran, sans quitter l'application. Le calendrier est celui de [ReviewPromptPolicy].
 *
 * La fenêtre n'apparaît que si l'application vient de Google Play (installation depuis le Play Store ou une piste de
 * test) et si le quota de Google n'est pas atteint : sinon la demande se termine sans rien afficher, et l'application
 * n'en est pas informée. Les erreurs sont donc ignorées (journalisées en debug) : une demande d'avis ne doit jamais
 * gêner l'utilisation.
 */
object InAppReview {

    private const val TAG = "InAppReview"

    /**
     * Compte ce lancement et, s'il est au calendrier, tente d'afficher la fenêtre d'avis sur [activity]. Après le dernier
     * lancement du calendrier, ne compte plus rien et n'affiche plus rien.
     */
    fun onAppLaunched(activity: Activity) {
        val launchCount = ReviewPromptPreferences(activity).recordLaunch() ?: return
        if (!ReviewPromptPolicy.shouldPrompt(launchCount)) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful) {
                AppLog.w(TAG, "demande d'avis indisponible", request.exception)
                return@addOnCompleteListener
            }
            if (activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
            manager.launchReviewFlow(activity, request.result)
        }
    }
}
