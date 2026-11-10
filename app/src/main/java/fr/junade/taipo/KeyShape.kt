package fr.junade.taipo

/**
 * Forme des touches du clavier (page « Apparence », section « Forme des touches »). Ne change que le dessin des touches : la
 * disposition, les zones tactiles ([TouchTargets]) et les couleurs restent les mêmes. Arrondi, la forme d'origine, est la forme par
 * défaut ; les quatre formes simples sont gratuites, les cinq plus marquées sont réservées au pass premium ([requiresPremium]).
 *
 * Chaque forme est décrite par :
 * - [body] : face pleine, simple trait ([FaceBody.OUTLINED]) ou aucune face au repos ([FaceBody.BARE]) ;
 * - [shadowFactor] : épaisseur de l'ombre, en multiple de `taipo_key_shadow_height` (0 : touche plate) ;
 * - [outline] et [cornerSize] : silhouette de la face (rectangle arrondi ou coins coupés) et taille de ses coins ;
 * - [faceWidth] : largeur dessinée (Rond dessine un cercle inscrit dans la touche) ;
 * - [radiusScale] : rayon des bulles (agrandissement, accents) par rapport au rayon de la charte.
 *
 * Logique pure, sans Android : testée par `KeyShapeTest`. Le dessin est fait par [KeyRenderer] et [ThemePreviewView].
 */
enum class KeyShape(val storageKey: String, val requiresPremium: Boolean) {
    /** Forme d'origine : coins de 8 dp, ombre de 2 dp. */
    ROUNDED("rounded", false),

    /** Capsule : coins entièrement arrondis. */
    PILL("pill", false),

    /** Coins presque droits (un quart du rayon de la charte, 2 dp). */
    SQUARE("square", false),

    /** Sans ombre ; l'appui assombrit la face au lieu de l'enfoncer. */
    FLAT("flat", false),

    /** Cercle inscrit dans la touche ; les touches larges (Maj, effacer, espace, Entrée…) restent en pilule. */
    ROUND("round", true),

    /** Coins coupés à 45°. */
    BEVEL("bevel", true),

    /** Keycap en relief : ombre épaisse et plateau éclairé sur le haut de la face. */
    MECHANICAL("mechanical", true),

    /** Touche creuse : un trait, sans ombre ; Entrée reste pleine. */
    OUTLINE("outline", true),

    /** Sans face au repos : la face n'apparaît que sous le doigt ; Entrée reste pleine. */
    BARE("bare", true),
    ;

    /** Corps de la face au repos. */
    enum class FaceBody { FILLED, OUTLINED, BARE }

    /** Silhouette de la face. */
    enum class Outline { ROUNDED_RECT, CHAMFER }

    /** Corps d'une touche ; Entrée ([accent]) est toujours pleine, pour rester trouvable d'un coup d'œil. */
    fun body(accent: Boolean): FaceBody = when {
        accent -> FaceBody.FILLED
        this == OUTLINE -> FaceBody.OUTLINED
        this == BARE -> FaceBody.BARE
        else -> FaceBody.FILLED
    }

    /** Épaisseur de l'ombre, en multiple de l'ombre de la charte. */
    val shadowFactor: Float
        get() = when (this) {
            FLAT, OUTLINE, BARE -> 0f
            MECHANICAL -> MECHANICAL_SHADOW_FACTOR
            else -> 1f
        }

    /** Vrai quand l'appui se voit à la couleur de la face (touche sans ombre à enfoncer). */
    val pressTintsFace: Boolean get() = shadowFactor == 0f

    /** Plateau éclairé sur le haut de la face (Mécanique). */
    val hasPlateau: Boolean get() = this == MECHANICAL

    fun outline(): Outline = if (this == BEVEL) Outline.CHAMFER else Outline.ROUNDED_RECT

    /** Vrai quand la touche est dessinée en cercle : forme Rond, touche à peu près carrée, hors Entrée. */
    fun isCircle(accent: Boolean, width: Float, height: Float): Boolean =
        this == ROUND && !accent && width <= height * ROUND_MAX_ASPECT

    /** Largeur dessinée de la face (le cercle de Rond, centré ; la touche entière sinon). */
    fun faceWidth(accent: Boolean, width: Float, height: Float): Float =
        if (isCircle(accent, width, height)) minOf(width, height) else width

    /** Hauteur dessinée de la face (le cercle de Rond, centré verticalement ; la face entière sinon). */
    fun faceHeight(accent: Boolean, width: Float, height: Float): Float =
        if (isCircle(accent, width, height)) minOf(width, height) else height

    /**
     * Taille des coins de la face de [width] × [height] : le rayon d'un rectangle arrondi, ou la longueur coupée pour [BEVEL].
     * [base] : rayon de la charte (`taipo_key_corner_radius`). Entrée ([accent]) est une pilule, sauf en Carré, Biseau et
     * Mécanique, où elle prend la forme des autres touches. Jamais plus que la moitié du plus petit côté.
     */
    fun cornerSize(accent: Boolean, width: Float, height: Float, base: Float): Float {
        val half = minOf(width, height) / 2f
        val size = when (this) {
            SQUARE -> base * SQUARE_RATIO
            BEVEL -> minOf(base * BEVEL_RATIO, half * BEVEL_MAX_RATIO)
            MECHANICAL, FLAT -> base * SOFT_RATIO
            PILL, ROUND -> half
            ROUNDED, OUTLINE, BARE -> base
        }
        val pillEnter = accent && this != SQUARE && this != BEVEL && this != MECHANICAL
        return if (pillEnter) half else minOf(size, half)
    }

    /** Rayon des bulles d'agrandissement et d'accents, en multiple du rayon de la charte. */
    val radiusScale: Float
        get() = when (this) {
            SQUARE, BEVEL -> SQUARE_RATIO
            FLAT, MECHANICAL -> SOFT_RATIO
            PILL, ROUND -> 2f
            ROUNDED, OUTLINE, BARE -> 1f
        }

    /** Utilisable maintenant, sachant si le pass premium est actif ([premium]). */
    fun isAvailable(premium: Boolean): Boolean = premium || !requiresPremium

    companion object {
        val DEFAULT = ROUNDED

        /** Mécanique : flanc de 5 dp (2,5 fois l'ombre de la charte). */
        const val MECHANICAL_SHADOW_FACTOR = 2.5f

        /** Carré : coins de 2 dp pour un rayon de charte de 8 dp. */
        const val SQUARE_RATIO = 0.25f

        /** Plat et Mécanique : coins de 5 dp. */
        const val SOFT_RATIO = 0.6f

        /** Biseau : coupe de 7 dp, au plus 60 % de la moitié du plus petit côté. */
        const val BEVEL_RATIO = 0.875f
        const val BEVEL_MAX_RATIO = 0.6f

        /** Rond : au-delà de ce rapport largeur / hauteur, la touche est large et reste en pilule. */
        const val ROUND_MAX_ASPECT = 1.25f

        fun fromStorageKey(key: String?): KeyShape = entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /** Forme à appliquer : celle choisie ([stored]) si elle est utilisable, sinon Arrondi (pass premium arrêté, par exemple). */
        fun effective(stored: KeyShape, premium: Boolean): KeyShape = if (stored.isAvailable(premium)) stored else DEFAULT
    }
}
