package fr.junade.taipo

/**
 * Touche dans la zone de saisie du prompt : retrouve la position dans le texte du prompt à partir de la position dans le
 * texte affiché.
 *
 * Le texte affiché n'est pas le prompt tel quel (voir `PromptBarView.renderInput`) : c'est la partie avant le curseur
 * (précédée de « … » si elle a été tronquée à gauche), puis le trait du curseur, puis la partie après le curseur.
 *
 * Logique pure (sans Android), testée en JVM.
 */
object PromptCaretMap {

    /**
     * @param displayOffset position touchée dans le texte affiché
     * @param displayedBeforeLength longueur affichée avant le trait du curseur (avec le « … » éventuel)
     * @param ellipsized la partie avant le curseur a été tronquée et commence par « … » (1 caractère)
     * @param caretLength longueur du trait du curseur dans le texte affiché
     * @param cursor position actuelle du curseur dans le prompt
     * @param textLength longueur du prompt
     * @return la position correspondante dans le prompt, entre 0 et [textLength]
     */
    fun textOffset(
        displayOffset: Int,
        displayedBeforeLength: Int,
        ellipsized: Boolean,
        caretLength: Int,
        cursor: Int,
        textLength: Int,
    ): Int {
        val ellipsisLength = if (ellipsized) 1 else 0
        val offset = when {
            // Sur le « … » ou avant lui : début de la partie visible (la partie masquée est atteinte par les flèches).
            displayOffset <= ellipsisLength -> cursor - (displayedBeforeLength - ellipsisLength)
            displayOffset <= displayedBeforeLength -> cursor - (displayedBeforeLength - displayOffset)
            displayOffset <= displayedBeforeLength + caretLength -> cursor // sur le trait du curseur
            else -> cursor + (displayOffset - displayedBeforeLength - caretLength)
        }
        return offset.coerceIn(0, textLength)
    }
}
