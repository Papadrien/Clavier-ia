package fr.junade.taipo

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import fr.junade.taipo.ai.SpanLocator

/**
 * Défait une insertion dans le champ de l'application : [inserted] (ce que le clavier a écrit) est remplacé par
 * [original] (ce qu'il y avait avant, vide pour une insertion pure).
 *
 * Le texte inséré est retrouvé par son contenu, au plus près du curseur : l'utilisateur a pu taper ou déplacer le
 * curseur depuis l'action. S'il n'est plus dans le champ, rien n'est modifié.
 */
internal object FieldUndo {

    fun replace(ic: InputConnection, inserted: String, original: String): Boolean {
        if (inserted.isEmpty()) return false
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = extracted?.text?.toString()
        ic.beginBatchEdit()
        try {
            if (extracted != null && text != null && extracted.startOffset >= 0) {
                val selStart = extracted.selectionStart.coerceIn(0, text.length)
                val selEnd = extracted.selectionEnd.coerceIn(0, text.length)
                val index = SpanLocator.find(text, inserted, selEnd - inserted.length)
                if (index < 0) return false
                val end = extracted.startOffset + index + inserted.length
                ic.setSelection(end, end)
                if (ic.getTextBeforeCursor(inserted.length, 0)?.toString() != inserted) {
                    ic.setSelection(extracted.startOffset + selStart, extracted.startOffset + selEnd)
                    return false
                }
                replaceBeforeCursor(ic, inserted, original)
                return true
            }
            // Position du texte inconnue (l'application ne fournit pas ExtractedText) : seul le texte juste avant le curseur.
            if (ic.getTextBeforeCursor(inserted.length, 0)?.toString() != inserted) return false
            replaceBeforeCursor(ic, inserted, original)
            return true
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun replaceBeforeCursor(ic: InputConnection, inserted: String, original: String) {
        ic.deleteSurroundingText(inserted.length, 0)
        if (original.isNotEmpty()) ic.commitText(original, 1)
    }
}
