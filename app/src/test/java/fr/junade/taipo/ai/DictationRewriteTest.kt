package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Transcription IA de Gemma 3 : découpage du texte dicté et garde-fou sur la reformulation. */
class DictationRewriteTest {

    @Test
    fun `un texte court reste en un seul morceau`() {
        assertEquals(listOf("Bonjour. Je serai en retard."), DictationRewrite.chunks("Bonjour.  Je serai en retard. "))
    }

    @Test
    fun `les morceaux sont coupes aux fins de phrase`() {
        val sentence = (1..30).joinToString(" ") { "mot$it" } + "."
        val chunks = DictationRewrite.chunks(List(5) { sentence }.joinToString(" "), maxWords = 100)
        assertEquals(listOf(90, 60), chunks.map { it.split(" ").size })
        assertTrue(chunks.all { it.endsWith(".") })
    }

    @Test
    fun `un texte sans ponctuation est coupe entre deux mots`() {
        val chunks = DictationRewrite.chunks((1..250).joinToString(" ") { "mot$it" }, maxWords = 100)
        assertEquals(listOf(100, 100, 50), chunks.map { it.split(" ").size })
    }

    @Test
    fun `texte vide, aucun morceau`() {
        assertEquals(emptyList<String>(), DictationRewrite.chunks("   "))
    }

    private val raw = "euh salut je voulais te dire que je serai en retard genre vingt minutes enfin non plutôt une demi heure"

    @Test
    fun `une reformulation fidele est acceptee`() {
        val rewritten = "Salut, je voulais te dire que je serai en retard, plutôt une demi-heure."
        assertEquals(rewritten, DictationRewrite.accept(raw, "  $rewritten\n"))
    }

    @Test
    fun `une reponse vide, tronquee ou trop longue est ecartee`() {
        assertNull(DictationRewrite.accept(raw, "  "))
        assertNull(DictationRewrite.accept(raw, "Salut."))
        assertNull(DictationRewrite.accept(raw, raw + " " + raw + " Et voici quelques conseils pour arriver à l'heure demain."))
    }

    @Test
    fun `une reponse a la question dictee est ecartee`() {
        val question = "est-ce que tu pourrais m'envoyer le devis pour la cuisine avant vendredi s'il te plaît"
        val answer = "Oui, bien sûr ! Je vous transmets cela rapidement, n'hésitez surtout pas à revenir vers moi ensuite."
        assertNull(DictationRewrite.accept(question, answer))
        assertEquals(
            "Est-ce que tu pourrais m'envoyer le devis pour la cuisine avant vendredi, s'il te plaît ?",
            DictationRewrite.accept(question, "Est-ce que tu pourrais m'envoyer le devis pour la cuisine avant vendredi, s'il te plaît ?"),
        )
    }

    @Test
    fun `un texte tres court n'est pas juge sur sa longueur`() {
        assertEquals("Oui.", DictationRewrite.accept("oui", "Oui."))
    }
}
