package fr.junade.taipo

/**
 * Marge sous les touches en portrait (réglage « Marge inférieure »), en dp. Elle réserve la zone système (barre de
 * geste, boutons de navigation) ; sa taille varie selon les appareils, d'où le curseur. [DEFAULT_DP] reprend la
 * valeur d'avant le réglage. Logique pure, testée sur JVM.
 */
object KeyboardBottomMargin {
    const val MIN_DP = 0
    const val MAX_DP = 100
    const val DEFAULT_DP = 60

    fun clamp(dp: Int): Int = dp.coerceIn(MIN_DP, MAX_DP)
}
