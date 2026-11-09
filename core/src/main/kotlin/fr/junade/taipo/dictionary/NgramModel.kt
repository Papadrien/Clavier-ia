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

    /** Mêmes paires, mêmes contextes et même total que [other] : sert aux tests ([parseBytes] face à [parse]). */
    internal fun sameContentAs(other: NgramModel): Boolean =
        totalCount == other.totalCount &&
            pairKeys.contentEquals(other.pairKeys) &&
            pairCounts.contentEquals(other.pairCounts) &&
            contextKeys.contentEquals(other.contextKeys) &&
            contextCounts.contentEquals(other.contextCounts)

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
         * Même résultat que [parse] sur le contenu UTF-8 [bytes] du fichier, sans créer de chaîne ni d'objet par ligne : les
         * empreintes sont calculées en décodant l'UTF-8 au fil de la lecture (mêmes caractères, donc mêmes clés, que les
         * chaînes de [parse]), rangées dans des tableaux d'entiers puis triées. C'est la lecture du clavier : environ
         * 600 000 lignes en français, que [parse] lisait en 690 ms sur un Pixel 9 (découpage, chaînes, `HashMap` en boîtes).
         *
         * Lignes séparées par `\n` (un `\r` final est ignoré) ; une paire ou un contexte répété garde sa dernière valeur, comme
         * dans [parse].
         */
        fun parseBytes(bytes: ByteArray): NgramModel {
            val pairs = KeyCounts()
            val contexts = KeyCounts()
            var total = 0L
            var lineStart = 0
            val size = bytes.size
            while (lineStart < size) {
                var lineEnd = lineStart
                while (lineEnd < size && bytes[lineEnd] != NEWLINE) lineEnd++
                val next = lineEnd + 1
                if (lineEnd > lineStart && bytes[lineEnd - 1] == CARRIAGE_RETURN) lineEnd--
                if (lineEnd > lineStart && bytes[lineStart] != HASH) total += parseLine(bytes, lineStart, lineEnd, pairs, contexts)
                lineStart = next
            }
            val (pk, pc) = pairs.sortedUnique()
            val (ck, cc) = contexts.sortedUnique()
            return NgramModel(pk, pc, ck, cc, total)
        }

        /**
         * Une ligne de [parseBytes] (ni vide ni commentaire), champs séparés par une espace comme `split(' ')` : deux champs
         * (contexte, rangé dans [contexts]) ou trois (paire, dans [pairs]) ; sinon la ligne est ignorée. Renvoie ce qu'elle
         * ajoute au total des contextes.
         */
        private fun parseLine(bytes: ByteArray, from: Int, to: Int, pairs: KeyCounts, contexts: KeyCounts): Long {
            var first = -1
            var second = -1
            var spaces = 0
            for (i in from until to) {
                if (bytes[i] != SPACE) continue
                spaces++
                when (spaces) {
                    1 -> first = i
                    2 -> second = i
                    else -> return 0L
                }
            }
            val countStart = (if (spaces == 1) first else second) + 1
            val count = if (spaces == 0) NOT_A_NUMBER else parseInt(bytes, countStart, to)
            if (count == NOT_A_NUMBER) return 0L
            val firstHash = mixUtf8(FNV_OFFSET, bytes, from, first)
            if (spaces == 1) {
                contexts.add(firstHash, count.toInt())
                return count
            }
            pairs.add(mixUtf8((firstHash xor 0x20L) * FNV_PRIME, bytes, first + 1, second), count.toInt())
            return 0L
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

        /**
         * [mix] sur les caractères (UTF-16, comme ceux d'une `String`) du texte UTF-8 de [bytes] entre [from] et [to]. Un octet
         * invalide compte pour un caractère de remplacement (U+FFFD) ; les fichiers livrés sont de l'UTF-8 valide.
         */
        private fun mixUtf8(hash: Long, bytes: ByteArray, from: Int, to: Int): Long {
            var h = hash
            var i = from
            while (i < to) {
                val decoded = decodeUtf8(bytes, i, to)
                val code = decoded and CODE_MASK
                if (code >= 0x10000) {
                    val offset = code - 0x10000
                    h = mixChar(h, 0xd800 + (offset ushr 10))
                    h = mixChar(h, 0xdc00 + (offset and 0x3ff))
                } else {
                    h = mixChar(h, code)
                }
                i += decoded ushr LENGTH_SHIFT
            }
            return h
        }

        /**
         * Point de code UTF-8 qui commence à [at] (avant [to]) : le point de code dans les bits [CODE_MASK], sa longueur en
         * octets au-dessus de [LENGTH_SHIFT]. Un octet invalide donne U+FFFD sur un octet.
         */
        private fun decodeUtf8(bytes: ByteArray, at: Int, to: Int): Int {
            val b0 = bytes[at].toInt() and 0xff
            val length = when {
                b0 < 0x80 -> return b0 or (1 shl LENGTH_SHIFT)
                b0 in 0xc2..0xdf -> 2
                b0 in 0xe0..0xef -> 3
                b0 in 0xf0..0xf4 -> 4
                else -> return INVALID
            }
            if (at + length > to) return INVALID
            var code = b0 and (0xff ushr (length + 1))
            for (k in 1 until length) {
                val b = bytes[at + k].toInt() and 0xff
                if (b and 0xc0 != 0x80) return INVALID
                code = (code shl 6) or (b and 0x3f)
            }
            return code or (length shl LENGTH_SHIFT)
        }

        private fun mixChar(hash: Long, code: Int): Long {
            val c = code.toLong()
            val h = (hash xor (c and 0xff)) * FNV_PRIME
            return (h xor (c ushr 8)) * FNV_PRIME
        }

        /** Entier décimal (signe facultatif) tenant dans un `Int`, comme `toIntOrNull` ; [NOT_A_NUMBER] sinon. */
        private fun parseInt(bytes: ByteArray, from: Int, to: Int): Long {
            if (from >= to) return NOT_A_NUMBER
            var i = from
            val negative = bytes[i] == MINUS
            if (negative || bytes[i] == PLUS) i++
            if (i >= to) return NOT_A_NUMBER
            var value = 0L
            while (i < to) {
                val digit = bytes[i] - ZERO
                if (digit !in 0..9) return NOT_A_NUMBER
                value = value * 10 + digit
                if (value > Int.MAX_VALUE.toLong() + 1) return NOT_A_NUMBER
                i++
            }
            val signed = if (negative) -value else value
            return if (signed in Int.MIN_VALUE..Int.MAX_VALUE) signed else NOT_A_NUMBER
        }

        private const val NEWLINE: Byte = 0x0A
        private const val CARRIAGE_RETURN: Byte = 0x0D
        private const val SPACE: Byte = 0x20
        private const val HASH: Byte = 0x23
        private const val MINUS: Byte = 0x2D
        private const val PLUS: Byte = 0x2B
        private const val ZERO: Byte = 0x30
        private const val NOT_A_NUMBER = Long.MIN_VALUE
        private const val LENGTH_SHIFT = 24
        private const val CODE_MASK = (1 shl LENGTH_SHIFT) - 1
        private const val INVALID = 0xfffd or (1 shl LENGTH_SHIFT) // U+FFFD sur un octet

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

/**
 * Empreintes et nombres lus par [NgramModel.parseBytes], dans l'ordre du fichier, sans boîte : deux tableaux qui grandissent.
 * [sortedUnique] les trie par empreinte (tri fusion, stable) et garde, pour une empreinte répétée, la dernière valeur lue.
 */
private class KeyCounts {
    private var keys = LongArray(INITIAL_CAPACITY)
    private var counts = IntArray(INITIAL_CAPACITY)
    private var size = 0

    fun add(key: Long, count: Int) {
        if (size == keys.size) {
            keys = keys.copyOf(size * 2)
            counts = counts.copyOf(size * 2)
        }
        keys[size] = key
        counts[size] = count
        size++
    }

    fun sortedUnique(): Pair<LongArray, IntArray> {
        val k = keys.copyOf(size)
        val c = counts.copyOf(size)
        mergeSort(k, c, LongArray(size), IntArray(size), 0, size)
        var unique = 0
        for (i in 0 until size) {
            if (unique > 0 && k[unique - 1] == k[i]) {
                c[unique - 1] = c[i] // même empreinte : la dernière lue l'emporte (le tri est stable)
            } else {
                k[unique] = k[i]
                c[unique] = c[i]
                unique++
            }
        }
        return k.copyOf(unique) to c.copyOf(unique)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
        const val INSERTION_SORT_MAX = 24

        fun mergeSort(keys: LongArray, counts: IntArray, keyBuffer: LongArray, countBuffer: IntArray, from: Int, to: Int) {
            if (to - from <= INSERTION_SORT_MAX) {
                for (i in from + 1 until to) {
                    val key = keys[i]
                    val count = counts[i]
                    var j = i - 1
                    while (j >= from && keys[j] > key) {
                        keys[j + 1] = keys[j]
                        counts[j + 1] = counts[j]
                        j--
                    }
                    keys[j + 1] = key
                    counts[j + 1] = count
                }
                return
            }
            val middle = (from + to) ushr 1
            mergeSort(keys, counts, keyBuffer, countBuffer, from, middle)
            mergeSort(keys, counts, keyBuffer, countBuffer, middle, to)
            if (keys[middle - 1] <= keys[middle]) return // déjà dans l'ordre
            System.arraycopy(keys, from, keyBuffer, from, to - from)
            System.arraycopy(counts, from, countBuffer, from, to - from)
            var left = from
            var right = middle
            for (i in from until to) {
                if (right >= to || (left < middle && keyBuffer[left] <= keyBuffer[right])) {
                    keys[i] = keyBuffer[left]
                    counts[i] = countBuffer[left]
                    left++
                } else {
                    keys[i] = keyBuffer[right]
                    counts[i] = countBuffer[right]
                    right++
                }
            }
        }
    }
}
