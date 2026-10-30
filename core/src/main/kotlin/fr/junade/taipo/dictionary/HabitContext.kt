package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.NextWordModel
import java.util.Locale

/**
 * Ce que les habitudes de l'utilisateur disent d'un candidat de correction, du plus fort au plus faible :
 * - [accepted] : combien de fois la paire « tapé -> candidat » est restée en place (0 tant qu'elle n'a pas atteint
 *   [CorrectionHabits.ACCEPTANCE_THRESHOLD] acceptations : une seule fois ne prouve rien) ;
 * - [context] : combien de fois le candidat a suivi le mot qui précède (bigrammes et trigrammes de [NextWordModel]) ;
 * - [usage] : combien de fois l'utilisateur a tapé et validé le candidat.
 *
 * L'ordre est lexicographique : une paire acceptée passe devant le contexte, qui passe devant l'usage.
 */
data class HabitScore(val accepted: Int, val context: Int, val usage: Int) : Comparable<HabitScore> {

    override fun compareTo(other: HabitScore): Int {
        accepted.compareTo(other.accepted).let { if (it != 0) return it }
        context.compareTo(other.context).let { if (it != 0) return it }
        return usage.compareTo(other.usage)
    }

    companion object {
        /** Aucune habitude connue : ne départage rien. */
        val NONE = HabitScore(0, 0, 0)
    }
}

/**
 * Habitudes consultées par [Dictionary.correctionFor] (phase 4) pour départager des candidats à distance d'édition
 * égale. Elles n'ajoutent aucun candidat et n'en écartent aucun : un candidat plus loin ne passe jamais devant un
 * plus proche, et sans habitude connue ([HabitScore.NONE] partout) le résultat est exactement celui d'avant.
 *
 * Réunit, pour une correction donnée : les compteurs ([habits], dans la langue [language]), les bigrammes de
 * [nextWords] (lecture seule) et le mot qui précède celui qu'on corrige ([previousWord], voir [previousWordOf]).
 * Logique pure (aucune dépendance Android), testée en JVM ; les lectures sont celles, synchronisées, des deux modèles.
 */
class HabitContext(
    private val habits: CorrectionHabits,
    private val language: KeyboardLanguage,
    private val nextWords: NextWordModel?,
    private val previousWord: String?,
    private val beforePreviousWord: String? = null,
) {

    /** Vrai si rien n'a été appris : [score] vaudrait [HabitScore.NONE] pour tout candidat (évite de le calculer). */
    fun isInert(): Boolean = habits.isEmpty() && (nextWords == null || nextWords.isEmpty())

    /** Score du candidat [candidate] pour le mot tapé [typed] (casse sans effet). */
    fun score(typed: String, candidate: String): HabitScore {
        val acceptedCount = habits.acceptedCount(language, typed, candidate)
        val accepted = if (acceptedCount >= CorrectionHabits.ACCEPTANCE_THRESHOLD) acceptedCount else 0
        val context = if (previousWord != null && nextWords != null) {
            val lower = candidate.lowercase(Locale.ROOT)
            // Paire (un mot) + triplet (deux mots, plus précis, plus lourd) : même pondération que la prédiction du mot suivant.
            nextWords.bigramCount(previousWord, lower) + trigramScore(nextWords, lower)
        } else {
            0
        }
        return HabitScore(accepted, context, habits.usageCount(language, candidate))
    }

    private fun trigramScore(model: NextWordModel, lowerCandidate: String): Int {
        val before = beforePreviousWord ?: return 0
        return NextWordModel.TRIGRAM_WEIGHT * model.trigramCount(before, previousWord ?: return 0, lowerCandidate)
    }

    /**
     * Tournure habituelle à mettre à la place de [typed] (même valide), ou null : voir [PhraseHabits]. Demande deux mots
     * de contexte sûrs (pas de début de phrase seul, pas de texte coupé, pas de nombre) et ne reprend pas une correction
     * que l'utilisateur a déjà annulée ([PhraseHabits.REFUSAL_UNDOS]). [isCandidate] : le mot est connu (dictionnaire ou
     * mot personnel). Le résultat est en minuscules : la casse du mot tapé est reprise par l'appelant.
     */
    fun phraseCorrectionFor(typed: String, isCandidate: (String) -> Boolean): String? {
        val model = nextWords ?: return null
        val first = previousWord?.takeIf { it != NextWordModel.SENTENCE_START } ?: return null
        val second = beforePreviousWord ?: return null
        val candidate = PhraseHabits.choose(typed, model.trigramFollowers(second, first), isCandidate) ?: return null
        if (habits.undoCount(language, typed, candidate) >= PhraseHabits.REFUSAL_UNDOS) return null
        return candidate
    }

    companion object {
        /**
         * Les deux éléments qui précèdent ce qui suit [textBeforeWord] (même règle que [previousWordOf] et que
         * l'apprentissage des triplets) : (mot ou début de phrase juste avant, élément encore avant ou null). Null s'il
         * n'y a pas de contexte sûr.
         */
        fun contextOf(textBeforeWord: String, truncated: Boolean): Pair<String, String?>? =
            NextWordModel.contextOf(textBeforeWord, truncated)

        /**
         * Le mot (ou [NextWordModel.SENTENCE_START] en début de phrase) qui précède ce qui suit [textBeforeWord], même
         * règle que l'apprentissage du modèle : au début d'un texte complet, c'est le début de phrase ; dans une fenêtre
         * coupée au début ([truncated]), le premier mot est peut-être incomplet et ne compte pas. Null s'il n'y a pas de
         * contexte sûr (texte coupé, nombre ou code juste avant).
         */
        fun previousWordOf(textBeforeWord: String, truncated: Boolean): String? {
            val tokens = NextWordModel.tokenize(textBeforeWord).toMutableList()
            if (truncated && tokens.isNotEmpty()) tokens.removeAt(0)
            if (tokens.isEmpty()) return if (truncated) null else NextWordModel.SENTENCE_START
            return tokens.last().takeIf { it != NextWordModel.BARRIER }
        }
    }
}
