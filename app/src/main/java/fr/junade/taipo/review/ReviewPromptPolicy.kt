package fr.junade.taipo.review

/**
 * Calendrier de la demande d'avis (fenêtre « bottom sheet » de Google Play In-App Review) : au 3e lancement de
 * l'application, puis tous les 20 lancements : 3e, 23e, 43e et 63e, soit 4 demandes au plus. Après le 63e lancement, plus
 * aucune demande et plus aucun comptage (voir [ReviewPromptPreferences.recordLaunch]).
 *
 * Logique pure, testée en JVM. Google Play ne dit jamais si la personne a laissé un avis (ni même si la fenêtre s'est
 * affichée) : le calendrier ne dépend donc pas de la réponse, et c'est Google Play qui décide d'afficher ou non la
 * fenêtre (quota par utilisateur).
 */
object ReviewPromptPolicy {

    /** Lancement auquel la première demande est faite. */
    const val FIRST_PROMPT_LAUNCH = 3

    /** Nombre de lancements entre deux demandes, à partir de la première. */
    const val REPEAT_EVERY_LAUNCHES = 20

    /** Dernier lancement où la demande est faite : (3e + 3 x 20). Au-delà, le calendrier est terminé pour de bon. */
    const val LAST_PROMPT_LAUNCH = 63

    /** Vrai si la demande d'avis doit être tentée au lancement numéro [launchCount] (1 = premier lancement). */
    fun shouldPrompt(launchCount: Int): Boolean =
        launchCount in FIRST_PROMPT_LAUNCH..LAST_PROMPT_LAUNCH && (launchCount - FIRST_PROMPT_LAUNCH) % REPEAT_EVERY_LAUNCHES == 0

    /** Vrai une fois le dernier lancement du calendrier atteint : il n'y a plus rien à demander ni à compter. */
    fun isFinished(launchCount: Int): Boolean = launchCount >= LAST_PROMPT_LAUNCH
}
