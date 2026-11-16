package fr.junade.taipo.analytics

/**
 * Règle de l'événement [AnalyticsEvents.KEYBOARD_USED] : au plus une fois par jour (jour **local** de l'appareil), à la
 * première touche tapée sur le clavier. Il garantit qu'un utilisateur qui se contente de taper (sans action IA, collage,
 * réglage…) est quand même vu chaque jour où il utilise le clavier. Logique pure (JVM), testée dans `:core` ; le jour
 * mémorisé et l'envoi sont dans `:app` (`Analytics.keyboardUsed`).
 */
object DailyKeyboardUse {

    /** Aucun jour mémorisé. */
    const val NO_DAY = Long.MIN_VALUE

    /** Jour local (jour époque décalé du fuseau) de l'instant [millis] ; [zoneOffsetMillis] : décalage du fuseau à cet instant. */
    fun localDay(millis: Long, zoneOffsetMillis: Int): Long =
        Math.floorDiv(millis + zoneOffsetMillis, UsageBuckets.DAY_MILLIS)

    /**
     * Vrai si l'événement n'a pas encore été envoyé le jour [today]. Un jour mémorisé au lendemain (changement de fuseau)
     * compte comme déjà envoyé.
     */
    fun shouldSend(lastSentDay: Long, today: Long): Boolean = lastSentDay == NO_DAY ||
        today > lastSentDay ||
        // Horloge reculée de plus d'un jour (réglage manuel, voyage) : on ne reste pas bloqué jusqu'à la date mémorisée.
        lastSentDay - today > 1
}
