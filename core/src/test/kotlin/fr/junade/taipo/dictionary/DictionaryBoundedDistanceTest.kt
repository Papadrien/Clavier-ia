package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Lot 1.4 : la distance bornée (arrêt anticipé) doit donner exactement le même verdict que la
 * distance complète pour la recherche de corrections : même valeur quand elle est <= borne,
 * valeur sentinelle `borne + 1` sinon.
 */
class DictionaryBoundedDistanceTest {

    private fun expected(a: String, b: String, max: Int): Int {
        val full = Dictionary.damerauLevenshtein(a, b)
        return if (full > max) max + 1 else full
    }

    @Test
    fun `cas connus`() {
        assertEquals(0, Dictionary.damerauLevenshteinBounded("chat", "chat", 2))
        assertEquals(1, Dictionary.damerauLevenshteinBounded("teh", "the", 1)) // inversion = 1
        assertEquals(1, Dictionary.damerauLevenshteinBounded("bnojour", "bonjour", 2))
        assertEquals(2, Dictionary.damerauLevenshteinBounded("bonjuor", "bonjourr", 2))
        assertEquals(3, Dictionary.damerauLevenshteinBounded("chat", "chien", 2)) // trop loin : borne + 1
        assertEquals(2, Dictionary.damerauLevenshteinBounded("", "ab", 2))
        assertEquals(3, Dictionary.damerauLevenshteinBounded("", "abc", 2))
        assertEquals(2, Dictionary.damerauLevenshteinBounded("abc", "", 1)) // écart de longueur > borne
    }

    @Test
    fun `identique a la distance complete sur des chaines aleatoires`() {
        val random = Random(20261003)
        val alphabet = "abcde" // petit alphabet : beaucoup d'inversions et de quasi-égalités
        fun word() = buildString { repeat(random.nextInt(0, 9)) { append(alphabet[random.nextInt(alphabet.length)]) } }
        repeat(20_000) {
            val a = word()
            val b = word()
            for (max in 0..3) {
                assertEquals(expected(a, b, max), Dictionary.damerauLevenshteinBounded(a, b, max), "a=$a b=$b max=$max")
            }
        }
    }

    @Test
    fun `identique a la distance complete sur des mutations de mots`() {
        val random = Random(42)
        val words = listOf("bonjour", "maintenant", "développement", "chat", "the", "restaurant", "où", "j'ai")
        repeat(5_000) {
            val a = words[random.nextInt(words.size)]
            val chars = a.toMutableList()
            repeat(random.nextInt(0, 4)) {
                when (random.nextInt(4)) {
                    0 -> if (chars.isNotEmpty()) chars.removeAt(random.nextInt(chars.size))
                    1 -> chars.add(random.nextInt(chars.size + 1), 'a' + random.nextInt(26))
                    2 -> if (chars.isNotEmpty()) chars[random.nextInt(chars.size)] = 'a' + random.nextInt(26)
                    else -> if (chars.size > 1) {
                        val i = random.nextInt(chars.size - 1)
                        val tmp = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = tmp
                    }
                }
            }
            val b = chars.joinToString("")
            for (max in 1..2) {
                assertEquals(expected(a, b, max), Dictionary.damerauLevenshteinBounded(a, b, max), "a=$a b=$b max=$max")
            }
        }
    }
}
