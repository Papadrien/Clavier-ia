package fr.junade.taipo

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import fr.junade.taipo.emoji.EmojiText

/**
 * Modifications du champ de l'application faites par le clavier hors de la frappe ordinaire (sortie de [TaipoIme], revue de
 * code) : retour arrière par caractère entier, effacement d'une sélection, Entrée, insertion et remplacement depuis le chat.
 * Chaque modification est enregistrée pour le bouton Annuler (sauf dans un champ sensible, voir [ImeHostContext]).
 *
 * [sendKey] : envoie une touche au champ (`InputMethodService.sendDownUpKeyEvents`), pour l'action de l'éditeur sur Entrée.
 */
internal class FieldEditor(
    private val ime: ImeHostContext,
    private val sendKey: (Int) -> Unit,
) {

    fun deleteBeforeCursor(count: Int) {
        ime.inputConnection()?.deleteSurroundingText(count, 0)
    }

    /**
     * Retour arrière : supprime le dernier « caractère » avant le curseur en entier. Un emoji peut
     * occuper plusieurs caractères UTF-16 (paire de substitution, teinte, drapeau, séquence ZWJ...) :
     * en supprimer un seul en laisserait une moitié illisible. Pour une lettre, c'est 1 caractère.
     */
    fun deleteLastCluster() {
        val ic = ime.inputConnection() ?: return
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)
        val length = if (before.isNullOrEmpty()) 1 else EmojiText.lastClusterLength(before).coerceAtLeast(1)
        if (!before.isNullOrEmpty() && before.length >= length) {
            val text = before.toString()
            val deleted = text.takeLast(length)
            ime.recordUndoDeletion(deleted, text.dropLast(length).takeLast(DeletionUndo.ANCHOR_LENGTH), text, mergeable = true) {
                selectionStartOffset(ic) - length
            }
        }
        ic.deleteSurroundingText(length, 0)
    }

    fun hasSelection(): Boolean {
        val ic = ime.inputConnection() ?: return false
        return !ic.getSelectedText(0).isNullOrEmpty() ||
            (ime.lastSelectionStart >= 0 && ime.lastSelectionEnd >= 0 && ime.lastSelectionStart != ime.lastSelectionEnd)
    }

    /** Efface le texte sélectionné dans le champ actif. Renvoie faux s'il n'y a pas de sélection. */
    fun deleteSelectedText(): Boolean {
        val ic = ime.inputConnection() ?: return false
        if (!hasSelection()) return false
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            val before = ic.getTextBeforeCursor(DeletionUndo.ANCHOR_LENGTH, 0)?.toString().orEmpty()
            val offset = selectionStartOffset(ic)
            ime.recordUndoDeletion(selected, before, before, mergeable = false) { offset }
        }
        ic.commitText("", 1) // remplace la sélection par du vide
        return true
    }

    /**
     * Story 5.3, « Ajouter le texte » : insère [text] à la position du curseur de l'application, sans rien
     * remplacer. Une sélection dans le champ est d'abord repliée à sa fin, pour que le texte sélectionné
     * reste en place. Rien n'est appris de ce texte. Faux si le champ n'est pas accessible.
     */
    fun insertGeneratedText(text: String): Boolean {
        val ic = ime.inputConnection() ?: return false
        ime.haptic()
        ime.clearHighlight() // un surlignage de correction ne vaut plus une fois le texte modifié
        ime.clearPendingAutocorrection()
        var toInsert = text
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val start = ime.lastSelectionStart
        val end = ime.lastSelectionEnd
        if (selected.isNotEmpty() || (start >= 0 && end >= 0 && start != end)) {
            if (start >= 0 && end >= 0) {
                val after = maxOf(start, end)
                ic.setSelection(after, after) // le texte sélectionné reste en place, l'ajout vient après
            } else {
                toInsert = selected + text // position inconnue : on remet le texte sélectionné, puis l'ajout
            }
        }
        ime.correction.markSelfEdit()
        ic.commitText(toInsert, 1)
        // Position inconnue : le texte sélectionné a été remis devant l'ajout (il remplaçait la sélection).
        ime.recordUndoEdit(inserted = toInsert, original = if (toInsert != text) selected else "")
        return true
    }

    /**
     * Bouton « Remplacer le texte » du chat : [text] remplace la sélection de l'application s'il y en a une,
     * sinon tout le texte accessible du champ. Une seule édition groupée (un seul « annuler » côté application).
     * Rien n'est appris de ce texte. Faux si le champ n'est pas accessible.
     */
    fun replaceFieldText(text: String): Boolean {
        val ic = ime.inputConnection() ?: return false
        ime.haptic()
        ime.clearHighlight()
        ime.clearPendingAutocorrection()
        ime.correction.markSelfEdit()
        var replaced = ""
        ic.beginBatchEdit()
        try {
            val selected = ic.getSelectedText(0)
            if (selected.isNullOrEmpty()) {
                val before = ic.getTextBeforeCursor(REPLACE_MAX_CHARS, 0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(REPLACE_MAX_CHARS, 0)?.toString().orEmpty()
                if (before.length + after.length > 0) {
                    ic.deleteSurroundingText(before.length, after.length)
                    replaced = before + after
                }
            } else {
                replaced = selected.toString()
            }
            ic.commitText(text, 1) // avec une sélection, commitText la remplace
        } finally {
            ic.endBatchEdit()
        }
        ime.recordUndoEdit(inserted = text, original = replaced)
        return true
    }

    /** Entrée : retour à la ligne dans un champ multiligne ou sans action, sinon l'action de l'éditeur (Envoyer, Rechercher…). */
    fun pressEnter() {
        val options = ime.editorInfo()?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val isMultiline = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val hasAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (isMultiline || !hasAction) {
            ime.inputConnection()?.commitText("\n", 1)
        } else {
            sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    companion object {
        /** « Remplacer le texte » : nombre maximal de caractères lus de part et d'autre du curseur pour vider le champ. */
        private const val REPLACE_MAX_CHARS = 10_000

        /** Position absolue du début de la sélection (ou du curseur) dans le champ, ou -1 si l'application ne la donne pas. */
        fun selectionStartOffset(ic: InputConnection): Int {
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
            if (extracted.startOffset < 0) return -1
            return extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
        }
    }
}
