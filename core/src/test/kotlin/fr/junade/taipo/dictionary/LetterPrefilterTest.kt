package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Le pré-filtre par les lettres de [Dictionary.correctionFor] doit donner exactement les mêmes corrections
 * que le parcours complet : il n'écarte que des candidats déjà trop loin.
 */
class LetterPrefilterTest {

    private val alphabet = "abcdefghijklmnopqrstuvwxyzéèàç'-"

    private fun randomWord(random: Random): String =
        String(CharArray(random.nextInt(2, 11)) { alphabet[random.nextInt(alphabet.length)] })

    private fun mutate(word: String, random: Random): String {
        val chars = word.toMutableList()
        for (step in 0 until random.nextInt(1, 4)) {
            if (chars.isEmpty()) break
            val i = random.nextInt(chars.size)
            when (random.nextInt(4)) {
                0 -> chars[i] = alphabet[random.nextInt(alphabet.length)]
                1 -> chars.removeAt(i)
                2 -> chars.add(i, alphabet[random.nextInt(alphabet.length)])
                else -> if (i + 1 < chars.size) {
                    val next = chars[i + 1]
                    chars[i + 1] = chars[i]
                    chars[i] = next
                }
            }
        }
        return chars.joinToString("")
    }

    @Test
    fun `le pre-filtre ne change aucune correction`() {
        val random = Random(7)
        val words = HashSet<String>().apply { while (size < 4000) add(randomWord(random)) }.toList()
        val entries = words.associateWith { random.nextLong(1, 100_000) }
        val rows = listOf(
            "azertyuiop".map { KeyProximity.KeyBox(it, 1f) },
            "qsdfghjklm".map { KeyProximity.KeyBox(it, 1f) },
            "wxcvbn".map { KeyProximity.KeyBox(it, 1f) },
        )
        val proximity = KeyProximity.fromRows(rows)
        val filtered = Dictionary.withFrequencies(entries, proximity = proximity, letterPrefilter = true)
        val full = Dictionary.withFrequencies(entries, proximity = proximity, letterPrefilter = false)

        var corrected = 0
        repeat(3000) {
            val typed = mutate(words[random.nextInt(words.size)], random)
            val expected = full.correctionFor(typed)
            if (expected != null) corrected++
            assertEquals(expected, filtered.correctionFor(typed), "mot tapé : $typed")
        }
        // Le jeu d'essai doit réellement exercer des corrections, sinon le test ne prouve rien.
        assertTrue(corrected > 100, "corrections trouvées : $corrected")
    }

    @Test
    fun `deux mots proches ont des masques compatibles`() {
        // « chat » -> « chats » : une lettre en plus ; « chat » -> « chut » : une substitution.
        val chat = Dictionary.letterMask("chat")
        assertEquals(1, (Dictionary.letterMask("chats") and chat.inv()).countOneBits())
        assertEquals(1, (chat and Dictionary.letterMask("chut").inv()).countOneBits())
        // Une inversion ne change pas l'ensemble des lettres.
        assertEquals(Dictionary.letterMask("teh"), Dictionary.letterMask("the"))
    }
}
