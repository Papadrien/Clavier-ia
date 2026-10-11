package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.NextWordModel
import fr.junade.taipo.suggestion.WordText

/**
 * Traduit ce que fait l'utilisateur en compteurs de [CorrectionHabits]. C'est le seul endroit où la règle est écrite ; le clavier ne fait que
 * signaler trois événements :
 * - [autocorrectionApplied] : une autocorrection vient d'être appliquée ;
 * - [autocorrectionUndone] : l'utilisateur vient de l'annuler (retour arrière juste après) ;
 * - [wordCompleted] : un mot vient d'être terminé (espace, retour à la ligne, suggestion choisie).
 *
 * Règles :
 * - **Annulation** : comptée si l'annulation porte sur une correction signalée par [autocorrectionApplied].
 * - **Acceptation** : une correction compte comme acceptée si, à la fin du mot suivant, le mot corrigé est toujours
 *   en place juste avant ce mot (ponctuation de fin de phrase ignorée). La règle lit le texte : elle ne dépend pas des
 *   nombreux endroits où le clavier oublie sa dernière correction.
 * - **Usage** : un mot terminé compte, sauf s'il vient d'être corrigé automatiquement (ce n'est pas l'utilisateur qui
 *   l'a tapé : le compter renforcerait les corrections elles-mêmes). Un nombre ou un code n'est jamais compté.
 *
 * Une correction oubliée (changement de champ, apprentissage interdit) n'est jamais comptée : [reset].
 * Logique pure (aucune dépendance Android), testée en JVM, à utiliser depuis le thread principal.
 */
class HabitSignals(private val habits: CorrectionHabits) {

    // [original] et [corrected] : le texte remplacé et son remplaçant tels que le clavier les applique (parfois
    // seulement la fin du mot : « ai » -> « 'ai »). [word] : le mot entier tel que tapé -> corrigé, ou null si la
    // correction ne change pas exactement un mot (rien n'est alors enregistré).
    private class Applied(
        val language: KeyboardLanguage,
        val original: String,
        val corrected: String,
        val word: Pair<String, String>?,
    ) {
        fun matches(otherOriginal: String, otherCorrected: String) = original == otherOriginal && corrected == otherCorrected
    }

    // Correction qui vient d'être appliquée : la fin du mot qu'elle remplace est le prochain mot terminé.
    private var incoming: Applied? = null

    // Correction dont le mot est terminé : elle attend la fin du mot suivant pour être acceptée.
    private var waiting: Applied? = null

    /** Événements enregistrés depuis la création (compteurs de debug : jamais de texte). */
    var recordedUndos = 0
        private set
    var recordedAccepted = 0
        private set
    var recordedUsages = 0
        private set

    /**
     * [textBeforeCursor] : le texte avant le curseur au moment de la correction (qui se termine par [original]) ;
     * [original] est remplacé par [corrected].
     */
    fun autocorrectionApplied(language: KeyboardLanguage, textBeforeCursor: String, original: String, corrected: String) {
        incoming = Applied(language, original, corrected, changedWord(textBeforeCursor, original.length, corrected))
    }

    /** Vrai si un compteur a changé (la sauvegarde doit être planifiée). */
    fun autocorrectionUndone(original: String, corrected: String): Boolean {
        val applied = when {
            incoming?.matches(original, corrected) == true -> incoming.also { incoming = null }
            waiting?.matches(original, corrected) == true -> waiting.also { waiting = null }
            else -> null
        } ?: return false
        val word = applied.word ?: return false
        val recorded = habits.recordUndo(applied.language, word.first, word.second)
        if (recorded) recordedUndos++
        return recorded
    }

    /**
     * [textBeforeCursor] : le texte qui précède le curseur, mot terminé inclus. Vrai si un compteur a changé
     * (la sauvegarde doit être planifiée).
     */
    fun wordCompleted(language: KeyboardLanguage, textBeforeCursor: String): Boolean {
        val words = NextWordModel.tokenize(textBeforeCursor).filter { it != NextWordModel.SENTENCE_START }
        val arrived = incoming
        val previous = waiting
        incoming = null
        waiting = null

        var changed = false
        if (previous != null && survives(words, previous)) {
            val word = previous.word
            if (word != null && habits.recordAccepted(previous.language, word.first, word.second)) {
                recordedAccepted++
                changed = true
            }
        }
        if (arrived != null) {
            waiting = arrived
        } else {
            val last = words.lastOrNull()
            if (last != null && last != NextWordModel.BARRIER && !NextWordModel.isEmoji(last) && habits.recordUsage(language, last)) {
                recordedUsages++
                changed = true
            }
        }
        return changed
    }

    /** Oublie les corrections en attente (changement de champ, apprentissage interdit ou désactivé). */
    fun reset() {
        incoming = null
        waiting = null
    }

    /** Le mot corrigé est juste avant le dernier mot : il est resté en place pendant toute la saisie du mot suivant. */
    private fun survives(words: List<String>, applied: Applied): Boolean {
        val corrected = NextWordModel.tokenize(applied.word?.second ?: return false).singleOrNull() ?: return false
        return words.size >= 2 && words[words.size - 2] == corrected
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * Le mot entier que la correction change : texte [textBeforeCursor] dont les [deleteCount] derniers caractères
         * sont remplacés par [insert]. Renvoie (mot tapé, mot corrigé), ou null si la correction ne change pas
         * exactement un mot (plusieurs mots modifiés, changement de casse seul, texte incohérent).
         */
        fun changedWord(textBeforeCursor: String, deleteCount: Int, insert: String): Pair<String, String>? {
            if (deleteCount < 0 || deleteCount > textBeforeCursor.length) return null
            val after = textBeforeCursor.substring(0, textBeforeCursor.length - deleteCount) + insert
            val before = textBeforeCursor.trim().split(WHITESPACE)
            val now = after.trim().split(WHITESPACE)
            if (before.size != now.size) return null
            var changed: Pair<String, String>? = null
            for (index in before.indices) {
                val was = before[index].trim { !WordText.isWordChar(it) }
                val becomes = now[index].trim { !WordText.isWordChar(it) }
                if (was.equals(becomes, ignoreCase = true)) continue
                if (changed != null || was.isEmpty() || becomes.isEmpty()) return null
                changed = was to becomes
            }
            return changed
        }
    }
}
