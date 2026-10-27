package fr.junade.taipo

/**
 * Story 1.13 : dimensions verticales du clavier selon l'orientation de l'écran. Logique pure
 * (sans dépendance Android) pour rester testable en JVM.
 *
 * En portrait, valeurs de référence calées sur Gboard (voir KeyboardView). En paysage, la hauteur
 * de l'écran est très réduite : rangées et marge basse sont raccourcies pour que le clavier
 * n'occupe pas presque tout l'écran, et le réglage de hauteur (story 1.12) est plafonné.
 */
data class KeyboardMetrics(val rowHeightDp: Float, val bottomMarginDp: Float, val maxHeightScale: Float) {

    /** Coefficient de hauteur réellement appliqué : le réglage de l'utilisateur, plafonné en paysage. */
    fun effectiveScale(heightScale: Float): Float = heightScale.coerceAtMost(maxHeightScale)

    /** Hauteur des rangées de touches (hors marge basse), en dp. */
    fun rowsHeightDp(rowCount: Int, heightScale: Float): Float =
        rowHeightDp * effectiveScale(heightScale) * rowCount

    /**
     * Marge basse effective, en dp. [navBarInsetDp] est l'inset réel de la barre de navigation fourni
     * par le système (0 si la barre de geste est masquée) ; null tant qu'il n'est pas connu, auquel
     * cas on garde la valeur de repli [bottomMarginDp].
     */
    fun resolvedBottomMarginDp(navBarInsetDp: Float?): Float =
        if (navBarInsetDp == null) bottomMarginDp else navBarInsetDp.coerceAtLeast(0f) + MIN_BOTTOM_GAP_DP

    /** Hauteur totale de la vue clavier (rangées + marge basse), en dp. */
    fun totalHeightDp(rowCount: Int, heightScale: Float): Float =
        rowsHeightDp(rowCount, heightScale) + bottomMarginDp

    companion object {
        /** Espace minimal sous les touches, même sans barre système. */
        const val MIN_BOTTOM_GAP_DP = 8f

        val PORTRAIT = KeyboardMetrics(rowHeightDp = 51.6f, bottomMarginDp = 60f, maxHeightScale = Float.MAX_VALUE)
        val LANDSCAPE = KeyboardMetrics(rowHeightDp = 36f, bottomMarginDp = 28f, maxHeightScale = 1.0f)

        fun forOrientation(isLandscape: Boolean): KeyboardMetrics = if (isLandscape) LANDSCAPE else PORTRAIT
    }
}
