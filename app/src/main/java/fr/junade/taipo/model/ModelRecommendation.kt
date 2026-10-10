package fr.junade.taipo.model

/**
 * Story 8.2 : modèle recommandé selon la RAM totale de l'appareil. Logique pure, testée en JVM.
 *
 * La recommandation est indicative : elle ne bloque jamais le téléchargement d'un autre modèle.
 *
 * `ActivityManager.MemoryInfo.totalMem` est la RAM totale vue par le système, légèrement inférieure à la RAM
 * annoncée par le constructeur (un téléphone « 8 Go » rapporte environ 7,3 Go). Les seuils sont en octets
 * décimaux et choisis pour tomber entre deux paliers commerciaux.
 */
object ModelRecommendation {

    /** En dessous : Léger recommandé (décision du backlog, story 8.2). */
    const val LIGHT_BELOW_BYTES = 4_000_000_000L

    /**
     * Seuil provisoire (point ouvert de la story 8.2) : à partir de cette RAM totale, Performant est recommandé
     * à la place d'Équilibré. À fixer après tests sur le Pixel 9 et au moins un appareil bas ou milieu de gamme.
     */
    const val PERFORMANT_FROM_BYTES = 7_000_000_000L

    fun recommended(totalMemBytes: Long): AiModel = when {
        totalMemBytes < LIGHT_BELOW_BYTES -> AiModel.LEGER
        totalMemBytes < PERFORMANT_FROM_BYTES -> AiModel.EQUILIBRE
        else -> AiModel.PERFORMANT
    }
}
