package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Accents oubliés, formes régulières absentes des listes, mots courts et inversions. */
class DictionaryAccentsTest {

    // Extrait des vraies fréquences de fr.txt.
    private val fr = Dictionary.withFrequencies(
        mapOf(
            "durée" to 2_512L,
            "dure" to 16_090L,
            "dures" to 1_657L,
            "ça" to 2_742_676L,
            "ca" to 189_898L,
            "était" to 985_841L,
            "etait" to 7_377L,
            "où" to 643_564L,
            "ou" to 497_358L,
            "année" to 47_747L,
            "cheval" to 5_000L,
        ),
        InflectionRules.FRENCH,
    )

    @Test
    fun `un pluriel regulier absent des listes n est pas modifie`() {
        // « durées » n'est pas dans la liste (seulement « durée ») : ce n'est ni « durée » ni « dures ».
        assertNull(fr.correctionFor("durées"))
        assertNull(fr.correctionFor("chevaux"))
    }

    @Test
    fun `un accent oublie sur un pluriel est retabli`() {
        assertEquals("durées", fr.correctionFor("durees"))
        assertEquals("Durées", fr.correctionFor("Durees"))
    }

    @Test
    fun `un accent oublie est retabli avant la distance d edition`() {
        assertEquals("année", fr.correctionFor("annee"))
    }

    @Test
    fun `une forme sans accent nettement moins frequente que la forme accentuee est corrigee`() {
        assertEquals("ça", fr.correctionFor("ca"))
        assertEquals("Ça", fr.correctionFor("Ca"))
        assertEquals("était", fr.correctionFor("etait"))
    }

    @Test
    fun `deux formes courantes avec et sans accent restent intactes`() {
        assertNull(fr.correctionFor("ou"))
        assertNull(fr.correctionFor("où"))
    }

    @Test
    fun `un mot personnel prime sur la correction d accent`() {
        assertNull(fr.correctionFor("ca", personalWords = listOf("ca")))
    }

    @Test
    fun `une base fautive du corpus ne valide pas sa forme fleschie`() {
        // « duree » est une faute du corpus (100 contre 2 512 pour « durée ») : « durees » n'en est pas
        // le pluriel valide, il faut le corriger en « durées ».
        val d = Dictionary.withFrequencies(mapOf("durée" to 2_512L, "duree" to 100L), InflectionRules.FRENCH)
        assertEquals("durées", d.correctionFor("durees"))
    }

    @Test
    fun `sans regles de flexion un pluriel absent des listes est corrige comme avant`() {
        val d = Dictionary.withFrequencies(mapOf("durée" to 2_512L, "dures" to 1_657L))
        assertEquals("durée", d.correctionFor("durées"))
    }

    @Test
    fun `les regles anglaises reconnaissent pluriels et conjugaisons regulieres`() {
        val en = Dictionary.withFrequencies(
            mapOf("like" to 10L, "city" to 10L, "walk" to 10L),
            InflectionRules.ENGLISH,
        )
        assertNull(en.correctionFor("liked"))
        assertNull(en.correctionFor("cities"))
        assertNull(en.correctionFor("walking"))
        assertNull(en.correctionFor("walks"))
    }

    @Test
    fun `un mot de quatre lettres n est corrige qu a distance un`() {
        val d = Dictionary.withFrequencies(mapOf("toit" to 100L))
        assertEquals("toit", d.correctionFor("tot")) // 1 modification
        assertNull(d.correctionFor("tabt")) // 2 modifications sur 4 lettres : trop
    }

    @Test
    fun `un mot de cinq lettres ou plus reste corrige a distance deux`() {
        val d = Dictionary.withFrequencies(mapOf("toits" to 100L))
        assertEquals("toits", d.correctionFor("tabts"))
    }

    @Test
    fun `teh n est jamais transforme en t en`() {
        val d = Dictionary.withFrequencies(
            mapOf("the" to 43_705L, "te" to 1_127_735L, "tes" to 199_689L, "t'en" to 1_257_857L),
        )
        assertEquals("the", d.correctionFor("teh"))
    }

    @Test
    fun `une inversion l emporte sur un mot plus frequent a distance egale`() {
        val d = Dictionary.withFrequencies(mapOf("the" to 10L, "te" to 1_000_000L))
        assertEquals("the", d.correctionFor("teh"))
    }

    @Test
    fun `isAdjacentSwap reconnait une inversion de deux lettres voisines`() {
        assertTrue(Dictionary.isAdjacentSwap("teh", "the"))
        assertTrue(Dictionary.isAdjacentSwap("bnojour", "bonjour"))
        assertFalse(Dictionary.isAdjacentSwap("chat", "chat"))
        assertFalse(Dictionary.isAdjacentSwap("teh", "ten"))
        assertFalse(Dictionary.isAdjacentSwap("abc", "cba")) // lettres non voisines
        assertFalse(Dictionary.isAdjacentSwap("ab", "abc"))
    }

    @Test
    fun `foldAccents retire accents cedille et developpe les ligatures`() {
        assertEquals("duree", Dictionary.foldAccents("durée"))
        assertEquals("ca", Dictionary.foldAccents("ça"))
        assertEquals("oeuvre", Dictionary.foldAccents("œuvre"))
        assertEquals("noel", Dictionary.foldAccents("noël"))
        assertEquals("bonjour", Dictionary.foldAccents("bonjour"))
    }
}
