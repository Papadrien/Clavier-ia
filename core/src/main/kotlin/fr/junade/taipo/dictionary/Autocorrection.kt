package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.WordText

/**
 * L'autocorrection d'un mot qui se termine (phase 5), en une seule fonction pure : correction du dictionnaire, puis, en
 * français, correction de contexte ([ContextualCorrection]), puis tournure habituelle ([Dictionary.habitPhraseCorrectionFor]),
 * le tout soumis aux refus de l'utilisateur. Le clavier ne fait que l'appeler ; la bande de suggestions lit les mêmes
 * éléments ([Dictionary.correctionFor], [ContextualCorrection.lastWordFix]) : le mot en gras est celui qui sera appliqué
 * (`AutocorrectionCoherenceTest`).
 */
object Autocorrection {

    /**
     * Le texte [textBeforeCursor] une fois son dernier mot terminé et corrigé (le même texte s'il n'y a rien à changer).
     * [textMayBeTruncated] : la fenêtre de texte est coupée au début ; [allowRetro] : autorise la révision du mot précédent
     * (désactivé juste après une annulation) ; [contextual] : appliquer aussi les corrections de contexte de [language] (français ou anglais) ;
     * [habitsFor] : les habitudes pour le dernier mot du texte qu'on lui passe (null : apprentissage désactivé ou interdit).
     * [stats] (phase 6, debug seulement : null sinon) : reçoit les décisions et la durée du calcul, sans jamais changer le
     * résultat ni retenir de texte (voir [AutocorrectStats]).
     */
    @Suppress("LongParameterList")
    fun correctedText(
        textBeforeCursor: String,
        textMayBeTruncated: Boolean,
        allowRetro: Boolean,
        dictionary: Dictionary,
        personalWords: Collection<String>,
        contextual: Boolean,
        habitsFor: (String) -> HabitContext?,
        stats: AutocorrectStats? = null,
        language: KeyboardLanguage = KeyboardLanguage.FR,
    ): String {
        val word = WordText.trailingWord(textBeforeCursor)
        if (word.isEmpty()) return textBeforeCursor
        val started = if (stats != null) System.nanoTime() else 0L
        val trace = if (stats != null) DecisionTrace() else null
        val habits = habitsFor(textBeforeCursor)
        // Une correction refusée deux fois est déjà écartée par le dictionnaire (voir [Dictionary.correctionFor]).
        val dictionaryFix = dictionary.correctionFor(word, personalWords = personalWords, habits = habits, trace = trace)?.takeIf { it != word }
        var result = if (dictionaryFix != null) textBeforeCursor.dropLast(word.length) + dictionaryFix else textBeforeCursor
        var contextApplied = false
        if (contextual) {
            ContextualCorrection.apply(result, textMayBeTruncated, allowRetro, dictionary::frequencyOf, habitsFor, language)?.let {
                if (it != result) contextApplied = true
                result = it
            }
        }
        // Tournure habituelle : rien d'autre n'a été corrigé, mais ce mot (même valide) n'est pas ce que les habitudes de
        // l'utilisateur mettent là, et il est à une faute de celui qu'elles mettent (voir PhraseHabits).
        var phraseApplied = false
        if (result == textBeforeCursor) {
            dictionary.habitPhraseCorrectionFor(word, personalWords, habits)?.takeIf { it != word }?.let {
                result = textBeforeCursor.dropLast(word.length) + it
                phraseApplied = true
            }
        }
        var output = textBeforeCursor
        var refusedAtEnd = false
        if (result != textBeforeCursor) {
            // Dernier garde-fou, quelle que soit l'origine de la correction : le mot réellement changé n'a pas été refusé.
            var common = 0
            val limit = minOf(result.length, textBeforeCursor.length)
            while (common < limit && result[common] == textBeforeCursor[common]) common++
            val changed = HabitSignals.changedWord(textBeforeCursor, textBeforeCursor.length - common, result.substring(common))
            if (changed != null && habits?.isRefused(changed.first, changed.second) == true) refusedAtEnd = true else output = result
        }
        if (stats != null && trace != null) {
            stats.recordWord(trace, dictionaryFix != null, contextApplied, phraseApplied, refusedAtEnd, System.nanoTime() - started)
        }
        return output
    }
}
