package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FrenchCanonicalizerTest {

    @Test
    fun `les apostrophes typographiques deviennent des apostrophes droites sans changer la longueur`() {
        val text = "l’homme d‘hier, qu′il dit ʼa"
        val normalized = FrenchCanonicalizer.normalizeApostrophes(text)
        assertEquals("l'homme d'hier, qu'il dit 'a", normalized)
        assertEquals(text.length, normalized.length)
        assertEquals("déjà droit", FrenchCanonicalizer.normalizeApostrophes("déjà droit"))
    }

    @Test
    fun `l'ecriture sans ligature remplace oe et OE`() {
        assertEquals("coeur", FrenchCanonicalizer.unligature("cœur"))
        assertEquals("Oeil", FrenchCanonicalizer.unligature("Œil"))
        assertEquals("poème", FrenchCanonicalizer.unligature("poème"))
    }

    @Test
    fun `la ligature n'est retenue que si elle pese au moins un tiers de l'autre ecriture`() {
        val words = arrayOf("cœur", "phœnix", "mœlleux", "œdème")
        val frequencies = longArrayOf(43_691L, 166L, 3L, 278L)
        val all = mapOf("coeur" to 44_598L, "phoenix" to 2_147L, "moelleux" to 401L, "oedème" to 234L)
        val result = FrenchCanonicalizer.ligatureRestorations(words, frequencies) { all[it] ?: 0L }
        assertEquals(mapOf("coeur" to "cœur", "oedème" to "œdème"), result)
    }
}
