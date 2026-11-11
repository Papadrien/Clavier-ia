package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Allemand : « ss » tapé pour « ß » sur un mot qui se découpe aussi en composé, et umlaut déjà tapé gardé (régression du banc, 07/11/2026). */
class GermanEszettRestorationTest {

    private val dictionary = Dictionary.withFrequencies(
        mapOf(
            "großzügig" to 5_000L,
            "gross" to 2_000L,
            "zügig" to 3_000L,
            "größer" to 40_000L,
            "großer" to 60_000L,
            "ich" to 5_000_000L,
        ),
        InflectionRules.GERMAN,
        41L,
        KeyProximity.NONE,
        accentPolicy = AccentPolicy.GERMAN,
    )

    @Test
    fun `un mot des listes a ss oublie l emporte sur le decoupage en compose`() {
        // « grosszügig » se découpe en « gross » + « zügig », mais c'est « großzügig » écrit sans ß.
        assertEquals("großzügig", dictionary.correctionFor("grosszügig"))
    }

    @Test
    fun `l umlaut deja tape est garde`() {
        // « großer » est plus fréquent, mais le « ö » tapé désigne « größer ».
        assertEquals("größer", dictionary.correctionFor("grösser"))
        // Sans umlaut tapé, la forme la plus fréquente reste choisie.
        assertEquals("großer", dictionary.correctionFor("grosser"))
    }
}
