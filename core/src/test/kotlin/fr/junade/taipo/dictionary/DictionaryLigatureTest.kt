package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Ligature « œ » (phase 1 de la refonte) : les listes contiennent « coeur » ET « cœur » à égalité ; seule l'écriture
 * avec la ligature est juste. Fréquences tirées de fr.txt.
 */
class DictionaryLigatureTest {

    private val fr = Dictionary.withFrequencies(
        mapOf(
            "coeur" to 44_598L, "cœur" to 43_691L, "soeur" to 32_041L, "sœur" to 32_449L,
            "oeuvre" to 5_434L, "œuvre" to 6_335L, "belle-soeur" to 819L, "belle-sœur" to 783L,
            "phoenix" to 2_147L, "phœnix" to 166L, "moelleux" to 401L, "mœlleux" to 3L,
            "moelle" to 5_000L, "poème" to 20_000L, "poete" to 90L, "poète" to 12_000L,
            "oedème" to 234L, "œdème" to 278L, "d'oeuvre" to 100L, "joe" to 25_803L,
        ),
        InflectionRules.FRENCH,
        100L,
    )

    @Test
    fun `un mot ecrit sans la ligature prend sa ligature`() {
        assertEquals("cœur", fr.correctionFor("coeur"))
        assertEquals("sœur", fr.correctionFor("soeur"))
        assertEquals("œuvre", fr.correctionFor("oeuvre"))
        assertEquals("belle-sœur", fr.correctionFor("belle-soeur"))
    }

    @Test
    fun `la majuscule est reprise y compris sur la ligature`() {
        assertEquals("Cœur", fr.correctionFor("Coeur"))
        assertEquals("Œuvre", fr.correctionFor("Oeuvre"))
    }

    @Test
    fun `le mot deja ecrit avec la ligature n'est pas touche`() {
        assertNull(fr.correctionFor("cœur"))
        assertNull(fr.correctionFor("Œuvre"))
    }

    @Test
    fun `les mots sans vraie forme avec ligature ne changent pas`() {
        assertNull(fr.correctionFor("phoenix")) // « phœnix » est 13 fois plus rare
        assertNull(fr.correctionFor("moelleux"))
        assertNull(fr.correctionFor("moelle"))
        assertNull(fr.correctionFor("joe"))
        assertNull(fr.correctionFor("poème"))
    }

    @Test
    fun `l'elision garde la ligature du mot qui suit`() {
        assertEquals("l'œuvre", fr.correctionFor("l'oeuvre"))
    }

    @Test
    fun `un accent oublie et une ligature se corrigent ensemble`() {
        assertEquals("œdème", fr.correctionFor("oedeme"))
    }

    @Test
    fun `un mot du dictionnaire personnel n'est jamais change`() {
        assertNull(fr.correctionFor("coeur", personalWords = listOf("coeur")))
    }
}
