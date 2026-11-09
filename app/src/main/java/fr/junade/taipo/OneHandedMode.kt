package fr.junade.taipo

/**
 * Mode une main : les touches sont serrées contre un bord de l'écran, à portée du pouce. Seule la largeur
 * change : la hauteur des rangées reste celle du réglage de hauteur, donc les touches deviennent plus étroites
 * (et non réduites en proportion comme avec une simple mise à l'échelle).
 */
enum class OneHandedMode(val storageKey: String) {
    /** Clavier sur toute la largeur (ou zone centrée plafonnée en classe large, story 1.14). */
    OFF("off"),

    /** Touches serrées contre le bord gauche de l'écran. */
    LEFT("left"),

    /** Touches serrées contre le bord droit de l'écran. */
    RIGHT("right"),
    ;

    val isActive: Boolean get() = this != OFF

    companion object {
        val DEFAULT = OFF

        fun fromStorageKey(key: String?): OneHandedMode = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/** Largeur de la zone des touches en mode une main, en pourcentage de la largeur normale. Logique pure (testable en JVM). */
object OneHandedWidth {
    const val MIN_PERCENT = 70
    const val MAX_PERCENT = 90
    const val DEFAULT_PERCENT = 80

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)
}
