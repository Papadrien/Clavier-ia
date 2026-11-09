package fr.junade.taipo.analytics

/**
 * Ancienneté de l'installation et nombre de jours d'utilisation, toujours en **tranches** (jamais la valeur exacte), ajoutés
 * à chaque événement pour estimer la rétention sans identifiant : Aptabase n'a pas d'utilisateur persistant, donc on décrit
 * le « profil » de l'événement (« une installation de 8 à 30 jours, active 4 à 7 jours ») au lieu de suivre une personne.
 * Logique pure (JVM), testée dans `:core` ; la lecture des dates et le stockage du compteur sont dans `:app`.
 *
 * Aucune valeur n'identifie quelqu'un : les tranches sont communes à tous les utilisateurs de même profil, et le compteur
 * reste sur l'appareil (seule la tranche part).
 */
object UsageBuckets {
    const val DAY_MILLIS = 86_400_000L

    /** Clés des propriétés ajoutées à chaque événement. */
    const val PROP_INSTALL_AGE = "install_age"
    const val PROP_ACTIVE_DAYS = "active_days"

    /** Ancienneté de l'installation en tranche. Une date incohérente (négative) compte comme « aujourd'hui ». */
    fun installAgeBucket(installAgeMillis: Long): String {
        val days = (installAgeMillis.coerceAtLeast(0L)) / DAY_MILLIS
        return when {
            days < 2 -> "0_1d"
            days < 8 -> "2_7d"
            days < 31 -> "8_30d"
            else -> "gt_30d"
        }
    }

    /** Nombre de jours distincts d'utilisation en tranche. */
    fun activeDaysBucket(activeDays: Int): String = when {
        activeDays <= 1 -> "1d"
        activeDays <= 3 -> "2_3d"
        activeDays <= 7 -> "4_7d"
        activeDays <= 30 -> "8_30d"
        else -> "gt_30d"
    }

    /** Compteur de jours d'utilisation : [count] jours distincts, dernier jour compté [lastDay] (jour époque, -1 si aucun). */
    data class ActiveDaysState(val count: Int, val lastDay: Long)

    /** Plafond du compteur : au-delà, la tranche ne change plus. */
    const val ACTIVE_DAYS_CAP = 1_000

    /** Compte [today] (jour époque UTC) une seule fois : un deuxième événement le même jour ne change rien. */
    fun recordActiveDay(state: ActiveDaysState, today: Long): ActiveDaysState =
        if (today == state.lastDay) state else ActiveDaysState((state.count + 1).coerceAtMost(ACTIVE_DAYS_CAP), today)

    /** Jour époque (UTC) d'un instant en millisecondes. */
    fun epochDay(millis: Long): Long = millis / DAY_MILLIS
}
