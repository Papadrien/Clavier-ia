package fr.junade.taipo

/**
 * Touches de la rangée du bas qu'on peut masquer depuis les paramètres du clavier : la touche emoji, la virgule et le point.
 * Une touche masquée cède sa largeur à la barre d'espace (la rangée garde ses 10 unités). Tout est affiché par défaut.
 */
data class BottomRowKeys(
    val emoji: Boolean = true,
    val comma: Boolean = true,
    val period: Boolean = true,
) {
    /** Vrai quand rien n'est masqué : la disposition d'origine est alors utilisée telle quelle. */
    val isAll: Boolean get() = emoji && comma && period

    companion object {
        val ALL = BottomRowKeys()
    }
}
