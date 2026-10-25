package fr.junade.taipo

/**
 * Action « supprimer » annulable depuis le bouton Annuler : [text] a été effacé du champ à la position [offset]
 * (-1 si l'application ne la fournit pas), juste après [anchor] (les derniers caractères qui précédaient la zone
 * effacée, pour vérifier au moment d'annuler que l'on réinsère au bon endroit).
 *
 * Des appuis successifs sur la touche retour arrière se regroupent en une seule action ([tryMerge]) : un appui sur
 * Annuler restitue tout ce qui vient d'être effacé d'affilée, pas un caractère à la fois (et l'historique de dix
 * actions n'est pas noyé par une suppression de mot).
 *
 * Logique pure (sans Android) : la réinsertion elle-même est confiée à [reinsert].
 */
internal class DeletionUndo(
    text: String,
    offset: Int,
    anchor: String,
    private val reinsert: (text: String, offset: Int, anchor: String) -> Boolean,
) : UndoHistory.Undo {

    var text: String = text
        private set

    var offset: Int = offset
        private set

    var anchor: String = anchor
        private set

    /**
     * Ajoute à cette action une suppression d'un caractère juste avant [deleted] : possible seulement si elle prolonge
     * exactement la précédente, c'est-à-dire si le texte qui précédait le curseur ([before], pris avant l'effacement)
     * se termine par l'[anchor] de cette action (le curseur n'a donc pas bougé). Renvoie faux sinon.
     */
    fun tryMerge(deleted: String, before: String): Boolean {
        if (deleted.isEmpty() || anchor.isEmpty() || !before.endsWith(anchor)) return false
        if (!before.endsWith(deleted) || deleted.length > anchor.length) return false
        if (text.length + deleted.length > MAX_CHARS) return false
        text = deleted + text
        if (offset >= 0) offset -= deleted.length
        anchor = before.dropLast(deleted.length).takeLast(ANCHOR_LENGTH)
        return true
    }

    override fun run(): Boolean = reinsert(text, offset, anchor)

    companion object {
        /** Nombre de caractères gardés avant la zone effacée pour contrôler le point de réinsertion. */
        const val ANCHOR_LENGTH = 20

        /** Au-delà, une suppression n'est plus regroupée avec la suivante (mémoire bornée). */
        const val MAX_CHARS = 5_000
    }
}
