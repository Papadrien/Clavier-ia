package fr.junade.taipo.analytics

/**
 * Règle de l'événement [AnalyticsEvents.FIRST_OPEN] : une seule fois par installation, pour compter les nouveaux
 * utilisateurs. Logique pure (JVM), testée dans `:core` ; la lecture des dates d'installation et du réglage est dans `:app`.
 *
 * Aptabase n'a pas d'identifiant d'appareil : le nombre d'événements `first_open` EST le nombre de nouvelles installations.
 * Deux cas à ne pas compter :
 * - l'application déjà ouverte avant la version qui apporte l'événement (mise à jour) : sans garde, tous les
 *   utilisateurs existants seraient comptés comme nouveaux à leur première ouverture après la mise à jour ;
 * - une ouverture déjà comptée (réglage mémorisé).
 */
object FirstOpenPolicy {

    /**
     * Écart minimal entre l'installation et la dernière mise à jour au-delà duquel l'application est considérée comme
     * mise à jour et non fraîchement installée. Sur une installation neuve, les deux dates sont égales (ou à quelques
     * millisecondes ou secondes près selon le magasin).
     */
    const val UPDATE_THRESHOLD_MILLIS = 60_000L

    enum class Decision {
        /** Première ouverture d'une nouvelle installation : envoyer l'événement, puis mémoriser. */
        SEND,

        /** Déjà traitée, ou application mise à jour : ne rien envoyer, et mémoriser pour ne plus se poser la question. */
        SKIP_AND_REMEMBER,

        /** Déjà mémorisée : rien à faire. */
        NOTHING,
    }

    fun decide(alreadyHandled: Boolean, firstInstallTime: Long, lastUpdateTime: Long): Decision = when {
        alreadyHandled -> Decision.NOTHING
        lastUpdateTime - firstInstallTime > UPDATE_THRESHOLD_MILLIS -> Decision.SKIP_AND_REMEMBER
        else -> Decision.SEND
    }
}
