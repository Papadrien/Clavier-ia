package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Accents sur des mots complets, élisions sans apostrophe et fautes du corpus (retour d'Adrien du 10/10/2026) :
 * « ecoute » -> « écoute », « tinquiete » -> « t'inquiète », « Lorsquun » -> « Lorsqu'un », « jetais » -> « j'étais ».
 */
class DictionaryTyposAndElisionsTest {

    // Extrait des vraies fréquences de fr.txt (corpus OpenSubtitles).
    private val fr = Dictionary.withFrequencies(
        mapOf(
            "écoute" to 81_813L, "ecoute" to 38_316L, "écoutez" to 40_000L,
            "corrigé" to 747L, "corrige" to 490L, "corriger" to 30_000L, "corrigez-moi" to 131L,
            "tué" to 98_218L, "tue" to 26_853L,
            "tâche" to 7_408L, "tache" to 3_325L,
            "était" to 985_841L, "étais" to 276_408L, "j'étais" to 276_408L, "jetais" to 293L,
            "inquiète" to 44_638L, "un" to 3_900_000L, "lorsque" to 18_327L, "lorsqu'un" to 5_000L,
            "est" to 6_942_248L, "et" to 5_000_000L, "été" to 400_000L, "ete" to 30_000L,
            "peux" to 594_578L, "oeux" to 8L, "qui" to 1_665_879L, "quoi" to 600_000L,
            "école" to 120_000L, "ami" to 116_639L, "où" to 643_564L, "ou" to 497_358L,
            "la" to 4_825_603L, "là" to 643_024L, "lã" to 3_000L,
        ),
        InflectionRules.FRENCH,
    )

    @Test
    fun `un mot complet sans accent dont la forme accentuee domine est corrige`() {
        assertEquals("écoute", fr.correctionFor("ecoute"))
        assertEquals("j'écoute", fr.correctionFor("j'ecoute"))
    }

    @Test
    fun `une paire e puis e accent final garde le seuil strict`() {
        assertNull(fr.correctionFor("tue")) // « tu tues » : tue est un vrai mot
        assertNull(fr.correctionFor("corrige")) // « je corrige » aussi
    }

    @Test
    fun `une forme sans accent qui est un vrai mot est conservee`() {
        assertNull(fr.correctionFor("tache"))
    }

    @Test
    fun `le mot complet sans accent propose sa forme accentuee en premier`() {
        assertEquals("corrigé", fr.suggestionsFor("corrige").first())
        assertEquals("où", fr.suggestionsFor("ou").first())
        assertEquals("là", fr.suggestionsFor("la").first())
    }

    @Test
    fun `les debris tres rares ne sont pas proposes comme forme accentuee`() {
        assertEquals(false, "lã" in fr.suggestionsFor("la"))
    }

    @Test
    fun `une apostrophe d'elision oubliee est retablie avec ses accents`() {
        assertEquals("t'inquiète", fr.correctionFor("tinquiete"))
        assertEquals("l'école", fr.correctionFor("lecole"))
        assertEquals("l'ami", fr.correctionFor("lami"))
    }

    @Test
    fun `une elision longue sans apostrophe est retablie`() {
        assertEquals("lorsqu'un", fr.correctionFor("lorsquun"))
        assertEquals("Lorsqu'un", fr.correctionFor("Lorsquun"))
    }

    @Test
    fun `une contraction ecrite sans apostrophe et sans accent est corrigee si tres dominante`() {
        assertEquals("j'étais", fr.correctionFor("jetais"))
        assertEquals("J'étais", fr.correctionFor("Jetais"))
    }

    @Test
    fun `un mot absent qui ressemble a une elision mais n'a pas de reste connu reste a la recherche normale`() {
        assertNull(fr.correctionFor("lxyzw"))
    }

    @Test
    fun `un mot sans accent n'est jamais propose comme correction quand sa forme accentuee domine`() {
        // « eet » est à une lettre de « est » et de « ete » (faute du corpus pour « été ») : seul « est » est une correction.
        assertEquals("est", fr.correctionFor("eet"))
    }

    @Test
    fun `une faute du corpus retiree des listes est corrigee`() {
        // « oeux » n'est plus dans la liste nettoyée (voir tools/PruneDictionaryTypos.kt) : le mot est inconnu, donc corrigé.
        val cleaned = Dictionary.withFrequencies(
            mapOf("peux" to 594_578L, "qui" to 1_665_879L, "quoi" to 600_000L),
            InflectionRules.FRENCH,
        )
        assertEquals("peux", cleaned.correctionFor("oeux"))
    }
}
