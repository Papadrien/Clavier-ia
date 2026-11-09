package fr.junade.taipo.dictionary

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Mots valides trop rares pour être proposés (« sédentaire ») : retrouvés par leurs accents ([RareWords]). */
class RareWordsTest {

    // 30 mots proposables avec « é » : l'accent est courant dans cette langue de test ; « í » ne l'est pas.
    private val common = (1..30).associate { "mot${'a' + it % 26}${'a' + it / 26}é" to 1_000L }

    private val dictionary = Dictionary.withFrequencies(
        common + mapOf(
            "dentaire" to 5_000L, "sédentaire" to 50L, "reflète" to 500L, "reflété" to 40L, "quindici" to 900L, "quíndí" to 30L,
            "ébénes" to 25L, "ebénés" to 25L, "rarissimé" to 8L, "insù" to 40L,
        ),
        InflectionRules.NONE,
        candidateMinFrequency = 100L,
        confidence = CorrectionConfidence.STANDARD,
    )

    @Test
    fun `un mot rare tape sans accent retrouve son accent`() {
        assertEquals("sédentaire", dictionary.correctionFor("sedentaire"))
        assertEquals("Sédentaire", dictionary.correctionFor("Sedentaire"))
        assertNull(dictionary.correctionFor("sédentaire"))
    }

    @Test
    fun `les accents tapes sont gardes`() {
        assertNull(dictionary.correctionFor("reflète"))
    }

    @Test
    fun `formes douteuses ecartees`() {
        assertNotEquals("quíndí", dictionary.correctionFor("quindi"), "« í » est rare dans la langue")
        assertNull(dictionary.correctionFor("rarissime"), "vue moins de 20 fois")
        assertNull(dictionary.correctionFor("ebenes"), "deux formes à égalité")
        assertNull(dictionary.correctionFor("insu"), "mot court")
    }

    @Test
    fun `vraie liste francaise - sedentaire et non dentaire`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "dictionaries/fr.txt").isFile } ?: return
        val french = Dictionary.withFrequencies(
            Dictionary.parseFrequencyBytes(File(root, "dictionaries/fr.txt").readBytes(), 5L),
            InflectionRules.FRENCH,
            candidateMinFrequency = 100L,
            confidence = CorrectionConfidence.STANDARD,
        )
        assertEquals("sédentaire", french.correctionFor("sedentaire"))
        assertEquals("sédentarité", french.correctionFor("sedentarite"))
        assertNull(french.correctionFor("sédentaire"))
    }
}
