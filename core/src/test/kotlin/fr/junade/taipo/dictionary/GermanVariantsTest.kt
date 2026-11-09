package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Allemand, phase 6 : variante suisse (« ss » à la place de « ß »). L'allemand d'Autriche n'a pas de règle propre. */
class GermanVariantsTest {

    private val frequencies = mapOf(
        "straße" to 60_000L, "heiß" to 40_000L, "groß" to 90_000L, "weiß" to 150_000L, "weiss" to 3_000L,
        "masse" to 9_000L, "maße" to 5_000L, "dass" to 3_100_000L, "gruß" to 8_000L,
    )

    private fun germanDictionary(words: Map<String, Long>) =
        Dictionary.withFrequencies(words, InflectionRules.NONE, 41L, KeyProximity.NONE, accentPolicy = AccentPolicy.GERMAN)

    private fun fix(dictionary: Dictionary, word: String): String? = dictionary.correctionFor(word, personalWords = emptyList(), habits = null)

    @Test
    fun `seules la Suisse et le Liechtenstein ecrivent en ss`() {
        assertTrue(GermanVariants.usesSwissSpelling("CH"))
        assertTrue(GermanVariants.usesSwissSpelling("li"))
        assertFalse(GermanVariants.usesSwissSpelling("DE"))
        assertFalse(GermanVariants.usesSwissSpelling("AT"))
        assertFalse(GermanVariants.usesSwissSpelling(""))
        assertFalse(GermanVariants.usesSwissSpelling(null))
    }

    @Test
    fun `la liste suisse n a plus de eszett et additionne les formes qui se rejoignent`() {
        val swiss = GermanVariants.swissFrequencies(frequencies)
        assertTrue(swiss.keys.none { 'ß' in it })
        assertEquals(153_000L, swiss["weiss"])
        assertEquals(14_000L, swiss["masse"])
        assertEquals(60_000L, swiss["strasse"])
    }

    @Test
    fun `une liste sans eszett est rendue telle quelle`() {
        val plain = mapOf("haus" to 5L)
        assertTrue(plain === GermanVariants.swissFrequencies(plain))
    }

    @Test
    fun `les noms suisses n ont plus de eszett ni de doublon`() {
        assertEquals(listOf("Weiss", "Mass", "Gruss"), GermanVariants.swissNames(listOf("Weiß", "Weiss", "Maß", "Gruß")))
    }

    @Test
    fun `en allemand d Allemagne ss est retabli en eszett`() {
        val german = germanDictionary(frequencies)
        assertEquals("weiß", fix(german, "weiss"))
        assertEquals("straße", fix(german, "strasse"))
    }

    @Test
    fun `en suisse ss est juste et n est pas corrige`() {
        val swiss = germanDictionary(GermanVariants.swissFrequencies(frequencies))
        assertNull(fix(swiss, "weiss"))
        assertNull(fix(swiss, "strasse"))
        assertNull(fix(swiss, "gruss"))
        // Une faute de frappe est corrigée vers la forme suisse.
        assertEquals("strasse", fix(swiss, "strase"))
    }

    @Test
    fun `le contexte connait weiss`() {
        // « Ich weiss, das du müde bist » -> « dass » (comme avec « weiß »), la frappe s'arrête sur « du ».
        assertEquals(
            "Ich weiss, dass du",
            GermanContextCorrector.correct("Ich weiss, das du", textMayBeTruncated = false, allowRetro = true, frequencyOf = { 0L }),
        )
    }
}
