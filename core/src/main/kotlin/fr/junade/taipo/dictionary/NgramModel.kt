package fr.junade.taipo.dictionary

import fr.junade.taipo.suggestion.WordText
import kotlin.math.ln

/**
 * Modèle de bigrammes livré avec le dictionnaire (feuille de route de l'autocorrection, point 1 : docs/autocorrection-roadmap.md).
 *
 * Il dit à quel point un mot est attendu juste après le mot précédent, d'après un corpus de phrases (Tatoeba, voir
 * `tools/build_ngrams.py`) : après « je », « suis » est bien plus probable que « suit » ; après « I », « am » plus que « an ».
 * L'autocorrecteur s'en sert pour départager ses candidats ([Dictionary.correctionFor] avec `previousWord`) et pour
 * reconnaître un mot juste qui n'est pas le bon mot ([Dictionary.realWordCorrectionFor]).
 *
 * Mémoire : les paires sont rangées par une empreinte de 64 bits dans un tableau trié (environ 12 octets par paire, 5 Mo pour
 * 400 000 paires), sans aucune chaîne. Deux paires différentes de même empreinte sont improbables (une sur 10^13 environ pour
 * ces tailles) et ne feraient que décaler un score.
 *
 * Logique pure, testée en JVM (`NgramModelTest`).
 */
class NgramModel private constructor(
    private val pairKeys: LongArray,
    private val pairCounts: IntArray,
    private val contextKeys: LongArray,
    private val contextCounts: IntArray,
    private val totalCount: Long,
) {

    /** Nombre de paires connues. */
    val size: Int get() = pairKeys.size

    /** Nombre de fois où [second] suit [first] dans le corpus (minuscules attendues). */
    fun pairCount(first: String, second: String): Int = lookup(pairKeys, pairCounts, pairKey(first, second))

    /** Nombre de fois où [word] est suivi d'un mot du dictionnaire (0 : mot inconnu du modèle, aucun avis). */
    fun contextCount(word: String): Int = lookup(contextKeys, contextCounts, wordKey(word))

    /**
     * Ce que le mot précédent [previous] apporte à [word], en logarithme naturel (même échelle que `ln(fréquence)` dans
     * [CandidateScore]) : ln(P(word | previous) / P(word)). Positif si [word] suit souvent [previous], négatif s'il ne le
     * suit presque jamais alors que [previous] est bien connu ; 0 si [previous] est inconnu du modèle. Borné à
     * ±[MAX_BONUS] : le contexte départage, il ne remplace pas la faute de frappe.
     *
     * P(word | previous) est lissé vers P(word) ([SMOOTHING] occurrences fictives) : un contexte vu peu de fois parle peu.
     */
    fun bonus(previous: String, word: String): Double {
        val context = contextCount(previous)
        if (context == 0) return 0.0
        val unigram = (contextCount(word) + 1.0) / (totalCount + contextKeys.size)
        val conditional = (pairCount(previous, word) + SMOOTHING * unigram) / (context + SMOOTHING)
        return ln(conditional / unigram).coerceIn(-MAX_BONUS, MAX_BONUS)
    }

    companion object {

        /** Occurrences fictives du lissage : un mot précédent vu 20 fois pèse à moitié. */
        const val SMOOTHING = 20.0

        /** Borne de [bonus] : e^4, environ 55 fois plus (ou moins) probable que sans contexte. */
        const val MAX_BONUS = 4.0

        /** Aucune paire : [bonus] vaut toujours 0. */
        val EMPTY = NgramModel(LongArray(0), IntArray(0), LongArray(0), IntArray(0), 0L)

        /**
         * Lit le format de `tools/build_ngrams.py` : « mot nombre » (total du contexte) ou « mot1 mot2 nombre » (paire). Les
         * lignes vides, les commentaires (#) et les lignes mal formées sont ignorés.
         */
        fun parse(lines: Sequence<String>): NgramModel {
            val pairs = HashMap<Long, Int>()
            val contexts = HashMap<Long, Int>()
            var total = 0L
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val parts = line.split(' ')
                when (parts.size) {
                    2 -> {
                        val count = parts[1].toIntOrNull() ?: continue
                        contexts[wordKey(parts[0])] = count
                        total += count
                    }
                    3 -> {
                        val count = parts[2].toIntOrNull() ?: continue
                        pairs[pairKey(parts[0], parts[1])] = count
                    }
                }
            }
            val (pk, pc) = sorted(pairs)
            val (ck, cc) = sorted(contexts)
            return NgramModel(pk, pc, ck, cc, total)
        }

        /**
         * Le mot qui précède le mot en cours, en minuscules, si le texte [textBeforeWord] (tout ce qui est avant ce mot) se
         * termine par ce mot et une seule espace : « je » pour « Alors je ». Null après une ponctuation, en début de texte ou
         * après deux espaces : le contexte est coupé, comme dans le corpus.
         */
        fun previousWordOf(textBeforeWord: String): String? =
            WordText.wordBeforeSingleSpace(textBeforeWord).takeIf { it.isNotEmpty() }?.lowercase()

        private fun sorted(map: HashMap<Long, Int>): Pair<LongArray, IntArray> {
            val keys = map.keys.toLongArray().also { it.sort() }
            val counts = IntArray(keys.size) { map.getValue(keys[it]) }
            return keys to counts
        }

        private fun lookup(keys: LongArray, counts: IntArray, key: Long): Int {
            val index = keys.binarySearch(key)
            return if (index >= 0) counts[index] else 0
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L

        private fun mix(hash: Long, s: String): Long {
            var h = hash
            for (c in s) {
                h = (h xor (c.code.toLong() and 0xff)) * FNV_PRIME
                h = (h xor (c.code.toLong() ushr 8)) * FNV_PRIME
            }
            return h
        }

        private fun wordKey(word: String): Long = mix(FNV_OFFSET, word)

        private fun pairKey(first: String, second: String): Long = mix((mix(FNV_OFFSET, first) xor 0x20L) * FNV_PRIME, second)
    }
}
