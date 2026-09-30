package fr.junade.taipo

/** Story 1.14 : classe de largeur de la fenêtre du clavier (Window Size Classes Android). */
enum class WidthClass {
    /** Moins de 600 dp (téléphone, pliable replié) : le clavier occupe toute la largeur. */
    COMPACT,

    /** 600 dp et plus (tablette, pliable déplié) : largeur plafonnée et clavier centré. */
    WIDE,
}

/**
 * Story 1.14 : zone horizontale occupée par les touches, en pixels dans la vue clavier. Logique
 * pure (sans dépendance Android) pour rester testable en JVM.
 *
 * - [WidthClass.COMPACT] (< 600 dp) : plein écran, comportement inchangé.
 * - [WidthClass.WIDE] (≥ 600 dp) : les touches profitent de la largeur supplémentaire, mais la
 *   largeur est plafonnée à [MAX_WIDTH_DP] et la zone des touches est centrée, pour éviter des
 *   rangées démesurément étirées sur tablette.
 *
 * La largeur utilisée est celle réellement disponible pour la vue (fenêtre de l'IME) : elle suit
 * donc le dépliage/repliage d'un pliable, la rotation et le mode multi-fenêtre.
 */
data class KeyboardWidth(val leftPx: Float, val widthPx: Float) {

    val rightPx: Float get() = leftPx + widthPx

    companion object {
        /** Seuil de la classe de largeur « Compact » (exclu), en dp. */
        const val COMPACT_BELOW_DP = 600f

        /** Largeur maximale de la zone des touches en classe large, en dp. */
        const val MAX_WIDTH_DP = 720f

        fun widthClass(availableWidthDp: Float): WidthClass =
            if (availableWidthDp < COMPACT_BELOW_DP) WidthClass.COMPACT else WidthClass.WIDE

        fun forAvailableWidth(availableWidthPx: Float, density: Float): KeyboardWidth {
            val available = availableWidthPx.coerceAtLeast(0f)
            if (density <= 0f) return KeyboardWidth(0f, available)
            return when (widthClass(available / density)) {
                WidthClass.COMPACT -> KeyboardWidth(0f, available)
                WidthClass.WIDE -> {
                    val width = minOf(available, MAX_WIDTH_DP * density)
                    KeyboardWidth((available - width) / 2f, width)
                }
            }
        }
    }
}
