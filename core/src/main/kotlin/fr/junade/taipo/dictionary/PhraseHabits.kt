package fr.junade.taipo.dictionary

import java.util.Locale

/**
 * Tournures habituelles : corriger un mot **même valide** quand les habitudes de l'utilisateur disent clairement autre
 * chose à cet endroit. Les habitudes sont les triplets « deux mots → mot suivant » que [fr.junade.taipo.suggestion.NextWordModel]
 * retient déjà (trigrammes) : après « je mange », l'utilisateur écrit presque toujours « des » ; si une frappe donne
 * « les » à la place, c'est probablement une faute de frappe, bien que « les » soit un mot du dictionnaire.
 *
 * Logique pure (aucune dépendance Android), testée en JVM. Elle choisit parmi [followers], les mots qui ont suivi le
 * contexte (jamais parmi tout le dictionnaire) : aucun mot nouveau n'arrive, seulement une tournure déjà écrite.
 *
 * « Clairement » veut dire, tous ensemble :
 * - le contexte a été vu au moins [MIN_CANDIDATE_COUNT] fois ;
 * - un mot en fait au moins [MIN_SHARE_PERCENT] % (donc au plus un mot peut gagner) ;
 * - le mot tapé en fait au plus [MAX_TYPED_SHARE_PERCENT] % (l'utilisateur ne l'écrit pas déjà là) ;
 * - ce mot est à **une seule** faute du mot tapé (lettre changée, ajoutée, retirée ou deux lettres inversées) ;
 * - ce mot est connu (dictionnaire ou mot personnel : une tournure faite d'une faute gardée n'est jamais reprise) ;
 * - le mot tapé a au moins [MIN_WORD_LENGTH] lettres (les mots de 1 à 2 lettres, comme « a » / « à », ont leurs propres règles).
 */
object PhraseHabits {

    /** Fois minimale où le mot habituel a suivi ce contexte. */
    const val MIN_CANDIDATE_COUNT = 4

    /** Part minimale (en %) du mot habituel parmi tout ce qui a suivi ce contexte. */
    const val MIN_SHARE_PERCENT = 80

    /** Part maximale (en %) du mot tapé parmi tout ce qui a suivi ce contexte. */
    const val MAX_TYPED_SHARE_PERCENT = 10

    /** Longueur minimale du mot tapé. */
    const val MIN_WORD_LENGTH = 3

    /**
     * Nombre d'annulations de cette correction à partir duquel elle n'est plus proposée. Un seul suffit : corriger un mot
     * valide est plus risqué que corriger une faute, donc le premier refus compte (voir `HabitContext.phraseCorrectionFor`).
     */
    const val REFUSAL_UNDOS = 1

    /**
     * Le mot habituel (en minuscules) à mettre à la place de [typed], ou null. [followers] : mots qui ont suivi le
     * contexte, avec leur compte ; [isCandidate] : le mot est-il un mot à proposer (connu du dictionnaire ou personnel).
     */
    fun choose(typed: String, followers: Map<String, Int>, isCandidate: (String) -> Boolean): String? {
        val lower = typed.lowercase(Locale.ROOT)
        if (lower.length < MIN_WORD_LENGTH || lower.any { it.isDigit() }) return null
        if (typed.all { it.isUpperCase() }) return null // acronyme
        var total = 0
        for (count in followers.values) total += count
        if (total < MIN_CANDIDATE_COUNT) return null
        if ((followers[lower] ?: 0) * 100 > total * MAX_TYPED_SHARE_PERCENT) return null
        for ((word, count) in followers) {
            if (count < MIN_CANDIDATE_COUNT || count * 100 < total * MIN_SHARE_PERCENT) continue
            if (word == lower || !isWord(word) || kotlin.math.abs(word.length - lower.length) > 1) continue
            if (EditDistance.damerauLevenshteinBounded(lower, word, 1) != 1) continue
            if (isCandidate(word)) return word
        }
        return null
    }

    /** Vrai pour un mot (lettres, apostrophe, trait d'union), pas pour un emoji, un début de phrase ou un code. */
    private fun isWord(token: String): Boolean =
        token.first().isLetter() && token.all { it.isLetter() || it == '\'' || it == '-' }
}
