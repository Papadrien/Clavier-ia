package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Point 5 de docs/autocorrection-roadmap.md : complétions malgré une faute dans le début tapé. */
class FuzzyCompletionTest {

    private val dictionary = Dictionary.withFrequencies(
        mapOf("aujourd'hui" to 90_000L, "ordinateur" to 20_000L, "ordinaire" to 5_000L, "bonjour" to 50_000L, "chat" to 9_000L),
        InflectionRules.NONE,
    )

    @Test
    fun `un debut a une faute pres retrouve le mot`() {
        assertEquals(listOf("aujourd'hui"), dictionary.fuzzyCompletions("aujoudr", 3)) // inversion
        assertEquals(listOf("ordinateur", "ordinaire"), dictionary.fuzzyCompletions("ordon", 3)) // touche remplacée
        assertEquals(listOf("bonjour"), dictionary.fuzzyCompletions("bnjo", 3)) // lettre oubliée
    }

    @Test
    fun `rien pour un debut exact ni trop court ni trop loin`() {
        assertTrue(dictionary.fuzzyCompletions("bonj", 3).isEmpty(), "déjà proposé par les complétions exactes")
        assertTrue(dictionary.fuzzyCompletions("cha", 3).isEmpty(), "moins de 4 lettres")
        assertTrue(dictionary.fuzzyCompletions("xyzw", 3).isEmpty())
    }

    @Test
    fun `la bande propose le mot malgre la faute`() {
        val slots = dictionary.suggestionSlotsFor("aujoudr")
        assertTrue(slots.any { it?.text == "aujourd'hui" }, slots.toString())
    }
}
