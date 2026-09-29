package fr.junade.taipo

/**
 * Story 1.12 : hauteur du clavier réglable depuis les paramètres. Chaque niveau applique un
 * coefficient à la hauteur des rangées de touches ; [NORMAL] (100 %) est la hauteur par défaut.
 * La marge basse (zone système) n'est pas redimensionnée.
 */
enum class KeyboardHeight(val storageKey: String, val scale: Float) {
    COMPACT("compact", 0.8f),
    SMALL("small", 0.9f),
    NORMAL("normal", 1.0f),
    LARGE("large", 1.1f),
    EXTRA_LARGE("extra_large", 1.2f),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun fromStorageKey(key: String?): KeyboardHeight = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
