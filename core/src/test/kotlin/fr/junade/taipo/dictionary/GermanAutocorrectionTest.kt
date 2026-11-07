package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Umlauts et ß (phase 2 du plan d'autocorrection allemande, 06/11/2026), sur un petit dictionnaire : politique
 * [AccentPolicy.GERMAN], repli ß / ss et transcription ae / oe / ue. Le banc (`AutocorrectBench --lang=de`) mesure l'ensemble sur
 * le vrai dictionnaire ; la décision entre deux mots justes (konnte / könnte) relève du contexte (phase 5), pas de ces tests.
 */
class GermanAutocorrectionTest {

    private val frequencies = mapOf(
        "für" to 300_000L, "über" to 200_000L, "können" to 150_000L, "müssen" to 90_000L, "schön" to 120_000L, "schon" to 400_000L,
        "konnte" to 170_000L, "könnte" to 150_000L, "wurde" to 800_000L, "würde" to 300_000L, "wurdest" to 4_000L, "würdest" to 20_000L,
        "ware" to 2_600L, "wäre" to 150_000L, "wunsche" to 300L, "wünsche" to 7_900L,
        "straße" to 60_000L, "heiß" to 40_000L, "groß" to 90_000L, "weiß" to 150_000L, "fußball" to 15_000L,
        "masse" to 9_000L, "maße" to 5_000L, "dass" to 3_100_000L, "mädchen" to 90_000L, "büro" to 20_000L, "frühstück" to 9_000L,
    )

    private val dictionary = Dictionary.withFrequencies(
        frequencies, InflectionRules.NONE, 41L, KeyProximity.NONE, accentPolicy = AccentPolicy.GERMAN,
    )

    private fun fix(word: String, dictionary: Dictionary = this.dictionary): String? =
        dictionary.correctionFor(word, personalWords = emptyList(), habits = null)

    @Test
    fun `un umlaut oublie sur un mot absent de la liste est retabli`() {
        assertEquals("für", fix("fur"))
        assertEquals("über", fix("uber"))
        assertEquals("können", fix("konnen"))
        assertEquals("müssen", fix("mussen"))
        assertEquals("Über", fix("Uber"))
    }

    @Test
    fun `une transcription ae oe ue est rattachee au mot a umlaut`() {
        assertEquals("für", fix("fuer"))
        assertEquals("können", fix("koennen"))
        assertEquals("schön", fix("schoen"))
        assertEquals("Mädchen", fix("Maedchen"))
        assertEquals("Frühstück", fix("Fruehstueck"))
    }

    @Test
    fun `ss pour eszett est retabli quand la forme en ss n'est pas un mot de la liste`() {
        assertEquals("Straße", fix("Strasse"))
        assertEquals("Fußball", fix("Fussball"))
        assertEquals("groß", fix("gross"))
    }

    @Test
    fun `ss pour eszett est retabli meme quand la forme en ss est listee mais tres rare`() {
        val withSwissForms = Dictionary.withFrequencies(
            frequencies + ("heiss" to 1_000L) + ("weiss" to 3_000L), InflectionRules.NONE, 41L, KeyProximity.NONE, accentPolicy = AccentPolicy.GERMAN,
        )
        assertEquals("heiß", fix("heiss", withSwissForms))
        assertEquals("weiß", fix("weiss", withSwissForms))
    }

    @Test
    fun `une paire de mots justes n'est pas tranchee par l'accent seul`() {
        // konnte / könnte, schon / schön, wurde / würde, wurdest / würdest : deux mots justes, le contexte décidera (phase 5).
        assertNull(fix("konnte"))
        assertNull(fix("schon"))
        assertNull(fix("wurde"))
        assertNull(fix("wurdest"))
    }

    @Test
    fun `les mots justes listes ne sont jamais remplaces`() {
        assertNull(fix("ware")) // la marchandise : 58 contre 1 ne suffit pas
        assertNull(fix("masse")) // masse / maße : les deux sont justes
    }

    @Test
    fun `un mot tres dominant est retabli meme s'il figure dans la liste`() {
        // 7 900 contre 300 : 26 fois, sous le seuil de 50 : le mot reste ; à 79 000 contre 300 (263 fois) il est rétabli.
        assertNull(fix("wunsche"))
        val dominant = Dictionary.withFrequencies(
            frequencies + ("wünsche" to 79_000L), InflectionRules.NONE, 41L, KeyProximity.NONE, accentPolicy = AccentPolicy.GERMAN,
        )
        assertEquals("wünsche", fix("wunsche", dominant))
    }

    @Test
    fun `une transcription presente dans la liste mais tres rare est retablie`() {
        val withTranscriptions = Dictionary.withFrequencies(
            frequencies + ("ueber" to 156L) + ("buero" to 11L), InflectionRules.NONE, 41L, KeyProximity.NONE, accentPolicy = AccentPolicy.GERMAN,
        )
        assertEquals("über", fix("ueber", withTranscriptions))
        assertEquals("Büro", fix("Buero", withTranscriptions))
    }

    @Test
    fun `les mots justes ne sont pas touches`() {
        for (word in listOf("für", "über", "straße", "dass", "mädchen", "büro")) assertNull(fix(word), word)
    }
}
