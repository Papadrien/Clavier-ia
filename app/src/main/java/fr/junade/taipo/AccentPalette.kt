package fr.junade.taipo

/**
 * Couleur d'accent du clavier (page « Thème », section « Couleur ») : remplace le violet de la charte par une autre teinte, en
 * clair comme en sombre. Le violet reste la couleur par défaut, gratuite ; les autres palettes sont prévues pour le pass premium
 * ([requiresPremium], verrou posé par [PremiumPass]).
 *
 * Chaque palette donne les cinq tons que la charte tire du violet :
 * - [primary] : `taipo_purple` (touche Entrée, bouton envoyer, sélections, accent sur touche claire) ;
 * - [secondary] : `taipo_purple_shadow` (épaisseur de la touche Entrée et des boutons violets) ;
 * - [onDarkKey] : `taipo_purple_light` (accent posé sur une touche sombre : chiffres d'appui long, trait de la barre d'espace) ;
 * - [darkKey] / [darkKeyShadow] : `taipo_key` et `taipo_key_shadow` de la palette sombre, gris teintés de l'accent.
 *
 * Le clavier lit ses couleurs par [KeyboardTheme.color] : la couleur résolue est passée dans [recolor], qui remplace un ton du violet
 * par le ton correspondant de la palette. Les rôles (`accent`, `key_enter`, `action_send`…) suivent donc sans être énumérés.
 * Sans effet en « Couleur dynamique », dont les tons viennent du fond d'écran. Les écrans de l'application restent violets.
 *
 * Contrastes (AccentPaletteTest) : blanc sur [primary] au moins 4,5:1, [primary] sur une touche secondaire claire au moins 3:1,
 * [onDarkKey] sur [darkKey] au moins 4,4:1 (le niveau du violet de la charte).
 */
enum class AccentPalette(
    val storageKey: String,
    val primary: Int,
    val secondary: Int,
    val onDarkKey: Int,
    val darkKey: Int,
    val darkKeyShadow: Int,
) {
    VIOLET("violet", 0xFF8C00FF.toInt(), 0xFF6800AB.toInt(), 0xFFC58BFF.toInt(), 0xFF43384C.toInt(), 0xFF27212D.toInt()),
    INDIGO("indigo", 0xFF4F46E5.toInt(), 0xFF3730A3.toInt(), 0xFFA5B4FC.toInt(), 0xFF3C3B4A.toInt(), 0xFF22222B.toInt()),
    BLUE("blue", 0xFF0A66E8.toInt(), 0xFF06429A.toInt(), 0xFF8DB8FF.toInt(), 0xFF39414B.toInt(), 0xFF21252C.toInt()),
    LAGOON("lagoon", 0xFF00796D.toInt(), 0xFF004F47.toInt(), 0xFF5FD4C6.toInt(), 0xFF384C4A.toInt(), 0xFF212C2B.toInt()),
    EMERALD("emerald", 0xFF15803D.toInt(), 0xFF0D5428.toInt(), 0xFF6EE7A0.toInt(), 0xFF3B4940.toInt(), 0xFF222A25.toInt()),
    AMBER("amber", 0xFFC2410C.toInt(), 0xFF842B06.toInt(), 0xFFFFA070.toInt(), 0xFF4B3F3A.toInt(), 0xFF2B2421.toInt()),
    RASPBERRY("raspberry", 0xFFD61F5B.toInt(), 0xFF930F3C.toInt(), 0xFFFF8AAE.toInt(), 0xFF4A3B40.toInt(), 0xFF2B2225.toInt()),
    MAGENTA("magenta", 0xFFB5179E.toInt(), 0xFF7A0C6A.toInt(), 0xFFF28AE0.toInt(), 0xFF4A3B48.toInt(), 0xFF2B2229.toInt()),
    GRAPHITE("graphite", 0xFF56606E.toInt(), 0xFF363C46.toInt(), 0xFFB7C0CC.toInt(), 0xFF414244.toInt(), 0xFF262627.toInt()),
    ;

    /** Réservée au pass premium ? Seul le violet, couleur de la marque, reste toujours proposé. */
    val requiresPremium: Boolean get() = this != DEFAULT

    /** Utilisable maintenant, sachant si le pass premium est actif ([premium]). */
    fun isAvailable(premium: Boolean): Boolean = premium || !requiresPremium

    /**
     * [color] (ARGB, déjà résolue dans la palette claire ou sombre de la charte) avec cette palette : un ton du violet ([DEFAULT])
     * devient le ton correspondant d'ici, toute autre couleur est rendue telle quelle. Pas d'allocation (appelée au dessin).
     */
    fun recolor(color: Int): Int {
        if (this == DEFAULT) return color
        return when (color) {
            DEFAULT.primary -> primary
            DEFAULT.secondary -> secondary
            DEFAULT.onDarkKey -> onDarkKey
            DEFAULT.darkKey -> darkKey
            DEFAULT.darkKeyShadow -> darkKeyShadow
            else -> color
        }
    }

    companion object {
        val DEFAULT = VIOLET

        fun fromStorageKey(key: String?): AccentPalette = entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /** Palette à appliquer : celle choisie ([stored]) si elle est utilisable, sinon le violet (pass premium arrêté, par exemple). */
        fun effective(stored: AccentPalette, premium: Boolean): AccentPalette = if (stored.isAvailable(premium)) stored else DEFAULT
    }
}

/**
 * Pass premium. Pas encore vendu : tout est ouvert ([isActive] vaut toujours vrai). Pour verrouiller les palettes de couleur (et
 * plus tard d'autres réglages), il suffira de brancher ici l'état réel de l'achat ; la page « Thème » et le clavier lisent cette
 * valeur, une palette réservée choisie sans pass retombe sur le violet ([AccentPalette.effective]).
 */
object PremiumPass {
    val isActive: Boolean get() = true
}
