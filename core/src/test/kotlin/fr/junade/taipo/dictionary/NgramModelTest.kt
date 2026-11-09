package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Point 1 de docs/autocorrection-roadmap.md : bigrammes livrés et leur effet sur la correction. */
class NgramModelTest {

    private val model = NgramModel.parse(
        sequenceOf(
            "# commentaire ignoré",
            "je 1000", "il 1000", "la 1000", "lune 10", "suis 10", "suit 10",
            "je suis 400", "il suit 50", "la lune 30", "ligne mal formée en trop",
        ),
    )

    @Test
    fun `les paires et les contextes sont lus`() {
        assertEquals(400, model.pairCount("je", "suis"))
        assertEquals(0, model.pairCount("suis", "je"))
        assertEquals(1000, model.contextCount("je"))
        assertEquals(0, model.contextCount("inconnu"))
        assertEquals(3, model.size)
    }

    @Test
    fun `la lecture sur octets donne le meme modele que la lecture par lignes`() {
        val lines = listOf(
            "# commentaire", "", "je 1000", "je suis 400", "il suit 50", "l'été 12", "l'été dernier 3", "crème brûlée 7",
            "œuf 5", "😀 2", "😀 ok 1", "je suis 410", "+7 +3", "neg -4", "trop 2147483648", "max 2147483647", "un deux trois 4",
            "vide  5", " 9", "mot", "mot pas_un_nombre", "fin 2\r", "fin du 3\r", "#je 5", "pas commentaire 3",
        )
        val text = lines.joinToString("\n") + "\nsans fin de ligne 1"
        val expected = NgramModel.parse(text.lineSequence())
        val parsed = NgramModel.parseBytes(text.toByteArray(Charsets.UTF_8))
        assertTrue(parsed.sameContentAs(expected))
        assertEquals(410, parsed.pairCount("je", "suis"))
        assertEquals(3, parsed.pairCount("l'été", "dernier"))
        assertEquals(1, parsed.pairCount("😀", "ok"))
        assertEquals(0, parsed.contextCount("trop"))
        assertEquals(3, parsed.pairCount("fin", "du"))
    }

    @Test
    fun `la lecture sur octets des bigrammes livres est identique a la lecture par lignes`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "dictionaries/bigrams").isDirectory }
            ?: return
        val files = File(root, "dictionaries/bigrams").listFiles { file -> file.name.endsWith(".txt") }.orEmpty()
        for (file in files) {
            val expected = file.bufferedReader(Charsets.UTF_8).useLines { NgramModel.parse(it) }
            val parsed = NgramModel.parseBytes(file.readBytes())
            assertTrue(parsed.sameContentAs(expected), file.name)
            assertTrue(parsed.size > 0, file.name)
        }
    }

    @Test
    fun `le bonus favorise le mot attendu et reste borne`() {
        assertTrue(model.bonus("je", "suis") > 0.0)
        assertTrue(model.bonus("je", "suit") < 0.0)
        assertEquals(0.0, model.bonus("inconnu", "suis"))
        assertTrue(model.bonus("je", "suis") <= NgramModel.MAX_BONUS)
        assertTrue(model.bonus("je", "suit") >= -NgramModel.MAX_BONUS)
    }

    @Test
    fun `le mot precedent n'est lu qu'apres une seule espace`() {
        assertEquals("je", NgramModel.previousWordOf("Alors Je "))
        assertNull(NgramModel.previousWordOf("Alors, "))
        assertNull(NgramModel.previousWordOf("je  "))
        assertNull(NgramModel.previousWordOf(""))
    }

    private val dictionary = Dictionary.withFrequencies(
        mapOf("je" to 9_000L, "il" to 9_000L, "la" to 9_000L, "suis" to 5_000L, "suit" to 5_000L, "lune" to 100L, "l'une" to 50_000L),
        InflectionRules.NONE,
        confidence = CorrectionConfidence.STANDARD,
        ngrams = model,
    )

    @Test
    fun `le mot precedent departage deux candidats`() {
        // « suiq » est à une touche de « suis » et de « suit », de même fréquence : sans contexte, c'est une égalité.
        assertNull(dictionary.correctionFor("suiq"))
        assertEquals("suis", dictionary.correctionFor("suiq", previousWord = "je"))
        assertEquals("suit", dictionary.correctionFor("suiq", previousWord = "il"))
    }

    @Test
    fun `un mot juste attendu apres le mot precedent n'est pas remplace par une contraction`() {
        assertEquals("l'une", dictionary.correctionFor("lune"))
        assertNull(dictionary.correctionFor("lune", previousWord = "la"))
    }

    @Test
    fun `la bande montre en gras la correction appliquee avec le contexte`() {
        for (text in listOf("je suiq", "il suiq")) {
            val corrected = Autocorrection.correctedText(text, false, true, dictionary, emptyList(), contextual = false, habitsFor = { null })
            val slots = dictionary.suggestionSlotsFor("suiq", previousWord = NgramModel.previousWordOf(text.dropLast(4)))
            val bold = slots.firstOrNull { it?.kind == WordSuggestion.Kind.AUTOCORRECTION }?.text
            assertEquals(corrected.substringAfterLast(' '), bold, text)
        }
    }
}
