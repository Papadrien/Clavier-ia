package fr.junade.taipo

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import fr.junade.taipo.suggestion.WordText

/**
 * Glissement de suppression depuis la touche retour arrière (story 1.9), extrait de [TaipoIme] (audit 2.1).
 *
 * Pendant le geste, rien n'est supprimé : la zone qui le serait (les mots précédant le curseur de départ, espaces de fin
 * compris) est sélectionnée dans le champ de l'application, ce qui la surligne ; revenir vers la droite la réduit. La
 * suppression a lieu au relâchement ([onRelease]). En mode prompt, la zone est seulement calculée sur le tampon du prompt
 * (pas de surlignage) et supprimée de la même façon.
 *
 * S'il n'y a pas de mot immédiatement avant le curseur (ponctuation), un seul caractère est visé pour que le geste ne
 * reste jamais sans effet.
 */
class DeleteSwipeController(private val host: Host) {

    /** Ce que le contrôleur demande au clavier : accès au champ, au prompt, à l'historique d'annulation et à l'état. */
    interface Host {
        /** Vrai si le mode prompt est actif (le geste agit alors sur le tampon du prompt, pas sur le champ). */
        fun promptActive(): Boolean

        fun inputConnection(): InputConnection?

        /** Dernière sélection connue du champ (repli quand l'application ne donne pas le texte extrait), ou -1. */
        fun lastSelectionStart(): Int

        fun lastSelectionEnd(): Int

        /** Mode prompt : position du curseur dans le tampon. */
        fun promptCursor(): Int

        /** Mode prompt : texte qui précède le curseur dans le tampon. */
        fun promptTextBeforeCursor(): String

        /** Mode prompt : supprime [count] caractères avant le curseur et rafraîchit la zone de saisie du prompt. */
        fun promptDeleteBefore(count: Int)

        /** Retour haptique de la suppression au relâchement. */
        fun haptic()

        /** Un cran à chaque changement de zone : toujours faible, absent si les vibrations sont désactivées. */
        fun hapticCursorMove()

        /**
         * Joue [sound] si le réglage « Son du clavier » est activé. Pendant le glissement la touche n'est jamais saisie
         * (donc jamais jouée par la frappe) : le contrôleur joue lui-même un cran à chaque changement de zone et la
         * « gomme » à la suppression.
         */
        fun playKeySound(sound: KeySound)

        fun clearHighlight()

        /** Oublie l'autocorrection en attente (le geste la rend caduque). */
        fun clearPendingAutocorrection()

        /** Point d'entrée unique de tout texte supprimé par l'utilisateur (story 2.4). */
        fun onUserTyped()

        fun recordUndoDeletion(deleted: String, anchor: String, before: String, mergeable: Boolean, offsetProvider: () -> Int)

        fun syncAutoCapitalization()

        fun applyState()

        fun updateCorrectionBarVisibility()
    }

    /** État du glissement en cours (offsets absolus dans le champ, ou dans le tampon en mode prompt). */
    private class State {
        var active = false
        var base = 0 // début de la sélection de départ (= curseur s'il n'y en avait pas)
        var end = 0 // fin de la sélection de départ
        var length = 0 // caractères surlignés à gauche de [base]
        var before = "" // texte précédant [base] au départ du geste
    }

    private val state = State()

    /** Vrai tant qu'un glissement de suppression est en cours. */
    val active: Boolean get() = state.active

    /** Abandonne le geste sans toucher au champ (changement de mode ou de champ en cours de geste). */
    fun cancel() {
        state.active = false
    }

    /**
     * Le doigt a glissé vers la gauche : [words] mots sont maintenant visés (0 = retour au point de départ).
     */
    fun onUpdate(words: Int) {
        if (host.promptActive()) {
            onPromptUpdate(words)
            return
        }
        val ic = host.inputConnection() ?: return
        if (!state.active) {
            if (!begin(ic)) return
        }
        val length = lengthForWords(words)
        if (length == state.length) return
        state.length = length
        host.hapticCursorMove()
        host.playKeySound(KeySound.CURSOR_MOVE)
        ic.setSelection(state.base - length, state.end)
    }

    /** Le doigt est relâché : la zone visée est supprimée (ou le geste annulé si le doigt est revenu au départ). */
    fun onRelease() {
        if (host.promptActive()) {
            onPromptRelease()
            return
        }
        val ic = host.inputConnection()
        val swipe = state
        if (!swipe.active || ic == null) {
            swipe.active = false
            return
        }
        swipe.active = false
        if (swipe.length == 0) {
            // Doigt revenu au point de départ : on annule, la sélection d'origine est rétablie.
            ic.setSelection(swipe.base, swipe.end)
            host.syncAutoCapitalization()
            host.applyState()
            return
        }
        host.haptic()
        host.playKeySound(KeySound.BACKSPACE)
        host.onUserTyped()
        ic.beginBatchEdit()
        try {
            ic.setSelection(swipe.end, swipe.end)
            val count = swipe.length + (swipe.end - swipe.base)
            val text = ic.getTextBeforeCursor(count + DeletionUndo.ANCHOR_LENGTH, 0)?.toString().orEmpty()
            if (text.length >= count) {
                host.recordUndoDeletion(
                    text.takeLast(count),
                    text.dropLast(count).takeLast(DeletionUndo.ANCHOR_LENGTH),
                    text,
                    false,
                ) {
                    swipe.end - count
                }
            }
            ic.deleteSurroundingText(count, 0)
        } finally {
            ic.endBatchEdit()
        }
        host.syncAutoCapitalization()
        host.applyState()
        host.updateCorrectionBarVisibility()
    }

    /** Le geste est interrompu par le système : la sélection d'origine est rétablie, rien n'est supprimé. */
    fun onCancel() {
        val swipe = state
        if (!swipe.active) return
        if (host.promptActive()) {
            swipe.active = false
            return
        }
        swipe.active = false
        host.inputConnection()?.setSelection(swipe.base, swipe.end)
        host.syncAutoCapitalization()
        host.applyState()
    }

    /** Capture le point de départ du glissement ; faux si la position du curseur est inconnue. */
    private fun begin(ic: InputConnection): Boolean {
        host.clearHighlight()
        host.clearPendingAutocorrection()
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val base: Int
        val end: Int
        val lastStart = host.lastSelectionStart()
        val lastEnd = host.lastSelectionEnd()
        if (extracted != null) {
            base = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
            end = extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd)
        } else if (lastStart >= 0 && lastEnd >= 0) {
            base = minOf(lastStart, lastEnd)
            end = maxOf(lastStart, lastEnd)
        } else {
            return false
        }
        // Une éventuelle sélection de départ est incluse dans la zone supprimée (comme la touche retour arrière seule).
        state.active = true
        state.base = base
        state.end = end
        state.length = 0
        state.before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        return true
    }

    /** Glissement de suppression en mode prompt : mots visés calculés sur le tampon, suppression au relâchement. */
    private fun onPromptUpdate(words: Int) {
        if (!state.active) {
            state.active = true
            state.base = host.promptCursor()
            state.end = host.promptCursor()
            state.length = 0
            state.before = host.promptTextBeforeCursor().takeLast(TEXT_CONTEXT_LOOKBEHIND)
        }
        val length = lengthForWords(words)
        if (length == state.length) return
        state.length = length
        host.hapticCursorMove()
        host.playKeySound(KeySound.CURSOR_MOVE)
    }

    private fun onPromptRelease() {
        val swipe = state
        if (!swipe.active) return
        swipe.active = false
        if (swipe.length == 0) return
        host.haptic()
        host.playKeySound(KeySound.BACKSPACE)
        host.promptDeleteBefore(swipe.length)
        host.syncAutoCapitalization()
        host.applyState()
    }

    /** Nombre de caractères couverts par [words] mots avant le point de départ (borné au texte connu). */
    private fun lengthForWords(words: Int): Int {
        var total = 0
        repeat(words) {
            val remaining = state.before.substring(0, state.before.length - total)
            val piece = wordToDeleteBeforeCursor(remaining)
            if (piece.isEmpty()) return total
            total += piece.length
        }
        return total
    }

    companion object {
        /** Portion de [textBeforeCursor], en partant de la fin, à supprimer pour la story 1.9. */
        internal fun wordToDeleteBeforeCursor(textBeforeCursor: String): String {
            if (textBeforeCursor.isEmpty()) return ""
            var start = textBeforeCursor.length
            while (start > 0 && (textBeforeCursor[start - 1] == ' ' || textBeforeCursor[start - 1] == '\t')) start--
            val afterTrailingSpace = start
            while (start > 0 && WordText.isWordChar(textBeforeCursor[start - 1])) start--
            return if (start < afterTrailingSpace) {
                textBeforeCursor.substring(start)
            } else if (afterTrailingSpace < textBeforeCursor.length) {
                textBeforeCursor.substring(afterTrailingSpace) // que des espaces/tabulations
            } else {
                textBeforeCursor.substring(textBeforeCursor.length - 1) // ni mot ni espace : un caractère
            }
        }
    }
}
