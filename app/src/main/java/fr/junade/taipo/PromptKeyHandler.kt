package fr.junade.taipo

import android.content.Context
import android.os.SystemClock
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.suggestion.EmailText
import fr.junade.taipo.suggestion.WordText

/**
 * Frappe en mode prompt (sortie de [TaipoIme], revue de code) : les touches et les mots suggérés vont dans le tampon du
 * prompt au lieu du champ de l'application, avec les mêmes traitements (annulation d'autocorrection, autocorrection à la fin
 * d'un mot, double espace, apprentissage, suggestions). La mémoire de l'autocorrection est partagée avec la frappe dans le champ.
 */
internal class PromptKeyHandler(
    private val context: Context,
    private val ime: ImeHostContext,
    private val controller: KeyboardController,
    private val autocorrections: AutocorrectionMemory,
) {

    private val prompt: PromptModeController get() = ime.prompt
    private val suggestions: SuggestionController get() = ime.suggestions

    /**
     * Une touche pressée en mode prompt : mêmes traitements que dans le champ de l'application, appliqués
     * au tampon du prompt au lieu de l'InputConnection : annulation d'autocorrection (suppression juste
     * après une correction), autocorrection du dictionnaire à la fin d'un mot, double espace, apprentissage
     * et suggestions. Le prompt est un champ de texte libre : ces règles ne dépendent pas du type du champ
     * de l'application (seul l'apprentissage suit `learningAllowed`, pour respecter un mode privé).
     */
    fun onKey(key: Key) {
        if (key.action == KeyAction.Emoji) {
            autocorrections.pending = null
            ime.emoji.showPanel()
            return
        }
        // Suppression juste après une autocorrection : on rétablit le mot tapé au lieu d'effacer un caractère.
        if (key.action == KeyAction.Backspace && undoLastAutocorrection()) {
            afterEdit()
            return
        }
        autocorrections.pending = null

        // Le mot qui vient de se terminer est vérifié juste avant que la touche qui le termine soit traitée, seulement s'il vient
        // d'être tapé (jamais à Entrée, qui envoie le prompt) : voir [AutocorrectionMemory.onKey].
        val autocorrection = if (autocorrections.onKey(key, SystemClock.uptimeMillis())) applyAutocorrection() else null

        // Double espace : l'espace précédente devient « . » (la correction d'un mot, elle, passe avant).
        val doubleSpace = key.action == KeyAction.Space && autocorrection == null && FieldType.TEXT.doubleSpacePeriod
        val doubleSpaceContext = if (doubleSpace) prompt.buffer.textBeforeCursor.takeLast(2) else null

        // Le contrôleur gère aussi Maj, le verrouillage des majuscules et la bascule des symboles.
        val result = controller.onKey(key, doubleSpaceContext, SystemClock.uptimeMillis())
        // La suppression précède l'insertion (double espace : on retire l'espace avant d'ajouter ". ").
        if (result.deleteBefore > 0) {
            if (key.action == KeyAction.Backspace) prompt.buffer.backspace() else prompt.buffer.deleteBefore(result.deleteBefore)
        }
        // Mot corrigé par l'espace alors qu'une espace suit déjà le curseur : on la remplace, pas de double espace.
        val spaceAlreadyAfter = prompt.buffer.textAfterCursor.startsWith(" ")
        if (autocorrection != null && key.action == KeyAction.Space && result.commit == " " && spaceAlreadyAfter) {
            prompt.buffer.deleteAfter(1)
        }
        result.commit?.let { prompt.buffer.insert(it) }
        val boundary = result.commit
        if (autocorrection != null && boundary != null) {
            autocorrections.pending = AppliedAutocorrection(autocorrection.original, autocorrection.corrected, boundary)
            suggestions.habitAutocorrectionApplied(autocorrection.textBefore, autocorrection.original, autocorrection.corrected)
        }
        // Un mot vient d'être terminé par une espace (pas une conversion en « . »).
        if (key.action == KeyAction.Space && result.deleteBefore == 0) ime.learnFromTyping("")

        prompt.refreshInput()
        // Un seul recalcul : seule une touche qui change le texte réinitialise le choix manuel de Maj.
        if (result.commit != null || result.deleteBefore > 0) ime.syncAutoCapitalization()
        ime.applyState()
        // Entrée envoie le prompt (la pilule est sur une seule ligne) : décision provisoire, point ouvert 6.2.
        if (result.isEnter) prompt.requestSend()
    }

    /** Après une modification du prompt hors frappe (suggestion touchée, correction annulée) : pilule, majuscule, suggestions, touches. */
    fun afterEdit() {
        prompt.refreshInput()
        ime.syncAutoCapitalization()
        ime.applyState()
    }

    /** Autocorrection du dictionnaire sur le mot qui précède le curseur du prompt (même règle que dans le champ). */
    private fun applyAutocorrection(): AppliedAutocorrection? {
        val before = prompt.buffer.textBeforeCursor.takeLast(TEXT_CONTEXT_LOOKBEHIND)
        val edit = suggestions.autocorrectionFor(before, autocorrections.allowContextualRetro(before)) ?: return null
        prompt.buffer.deleteBefore(edit.deleteCount)
        prompt.buffer.insert(edit.insert)
        val original = before.takeLast(edit.deleteCount)
        suggestions.recordCorrection(before, edit)
        return AppliedAutocorrection(original = original, corrected = edit.insert, textBefore = before)
    }

    /**
     * Rétablit le mot tel que tapé si la dernière action était une autocorrection du prompt (mot corrigé
     * et séparateur juste avant le curseur). Renvoie faux si rien n'a été annulé.
     */
    private fun undoLastAutocorrection(): Boolean {
        val pending = autocorrections.pending ?: return false
        autocorrections.pending = null
        if (pending.isUndoWindowExpired()) return false // trop tard : Supprimer efface un caractère comme d'habitude
        val expected = pending.corrected + pending.boundary
        if (!prompt.buffer.textBeforeCursor.endsWith(expected)) return false // le texte ou le curseur a changé entre-temps
        prompt.buffer.deleteBefore(expected.length)
        prompt.buffer.insert(pending.original + pending.boundary)
        autocorrections.onReverted(pending.original + pending.boundary)
        suggestions.habitAutocorrectionUndone(pending.original, pending.corrected)
        Analytics.track(context, AnalyticsEvents.AUTOCORRECT_UNDONE)
        return true
    }

    /** Touche sur un mot suggéré en mode prompt (mêmes règles que pour un mot suggéré dans le champ). */
    fun onWordSuggestionTapped(suggestion: WordSuggestion) {
        if (suggestion !in suggestions.currentWordSuggestions) return
        if (suggestion.kind == WordSuggestion.Kind.PREDICTION) {
            ime.haptic()
            autocorrections.pending = null
            prompt.buffer.insert("${suggestion.text} ")
            ime.learnFromTyping("")
            afterEdit()
            return
        }
        if (suggestion.kind == WordSuggestion.Kind.COMPOUND) {
            val before = prompt.buffer.textBeforeCursor
            val second = WordText.trailingWord(before)
            val first = WordText.wordBeforeSingleSpace(before.dropLast(second.length))
            if (second.isEmpty() || first.isEmpty()) return
            ime.haptic()
            autocorrections.pending = null
            prompt.buffer.deleteBefore(first.length + 1 + second.length)
            prompt.buffer.insert("${suggestion.text} ")
            ime.learnFromTyping("")
            afterEdit()
            return
        }
        if (suggestion.kind == WordSuggestion.Kind.EMAIL) {
            val before = prompt.buffer.textBeforeCursor.takeLast(TEXT_CONTEXT_LOOKBEHIND)
            val typedEmailStart = EmailText.trailingToken(before, truncated = before.length >= TEXT_CONTEXT_LOOKBEHIND) ?: return
            ime.haptic()
            autocorrections.pending = null
            prompt.buffer.deleteBefore(typedEmailStart.length)
            prompt.buffer.insert("${suggestion.text} ")
            ime.learnFromTyping("")
            afterEdit()
            return
        }
        val typedBefore = WordText.trailingWord(prompt.buffer.textBeforeCursor)
        // Curseur placé dans un mot : le mot entier est visé ; une espace déjà présente après lui est remplacée.
        val textAfter = prompt.buffer.textAfterCursor
        val wordAfter = WordText.leadingWord(textAfter)
        val typed = typedBefore + wordAfter
        if (typed.isEmpty()) return
        val spaceAfter = textAfter.getOrNull(wordAfter.length) == ' '
        val separator = TypingRules.separatorAfterSuggestion(textAfter, wordAfter) // pas d'espace ajoutée devant une virgule
        ime.haptic()
        // Le mot tel que tapé peut différer du mot affiché à gauche : forme d'origine d'un mot déjà corrigé.
        if (suggestion.kind != WordSuggestion.Kind.TYPED || wordAfter.isNotEmpty() || suggestion.text != typed) {
            prompt.buffer.deleteBefore(typedBefore.length)
            prompt.buffer.deleteAfter(wordAfter.length + if (spaceAfter) 1 else 0)
            prompt.buffer.insert("${suggestion.text}$separator")
        } else if (separator.isNotEmpty()) {
            if (spaceAfter) prompt.buffer.deleteAfter(1)
            prompt.buffer.insert(separator)
        }
        autocorrections.pending = if (suggestion.replacesOnSpace) {
            suggestions.recordCorrection(typed, suggestion.text)
            AppliedAutocorrection(original = typed, corrected = suggestion.text, boundary = separator)
        } else {
            null
        }
        ime.learnFromTyping("") // le mot choisi (complété ou corrigé) est celui qui compte pour la prédiction
        afterEdit()
    }
}
