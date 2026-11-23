package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Transcription en direct (23/11/2026) : découpage du texte dicté au fil de la parole, et recollage des blocs reformulés. */
class LiveRewriteTest {

    private fun words(n: Int, from: Int = 0) = (from until from + n).joinToString(" ") { "mot$it" }

    @Test
    fun `rien tant que le passage en cours n'est pas suivi d'autre chose`() {
        assertNull(LiveRewrite.nextBlock(words(10), consumed = 0))
        assertNull(LiveRewrite.nextBlock("Bonjour à tous.", consumed = 0, maxWords = 5))
    }

    @Test
    fun `un bloc complet part des que la suite commence`() {
        val raw = words(70)
        val block = LiveRewrite.nextBlock(raw, consumed = 0, maxWords = 60)
        assertNotNull(block)
        block!!
        assertEquals(60, block.words)
        assertEquals(words(60), block.text)
        assertNull(LiveRewrite.nextBlock(raw, consumed = 60, maxWords = 60))
        assertEquals(words(10, from = 60), LiveRewrite.rest(raw, consumed = 60))
    }

    @Test
    fun `la coupe se fait en fin de phrase`() {
        val raw = "Première phrase assez courte. Deuxième phrase qui continue encore"
        val block = LiveRewrite.nextBlock(raw, consumed = 0, maxWords = 6)!!
        assertEquals("Première phrase assez courte.", block.text)
        assertEquals(4, block.words)
        assertEquals("Deuxième phrase qui continue encore", LiveRewrite.rest(raw, block.words))
    }

    @Test
    fun `les blocs se recollent en prose, avec un retour a la ligne autour d'une liste`() {
        assertEquals("Un. Deux.", LiveRewrite.join(listOf("Un.", " Deux. ")))
        assertEquals("Courses :\n- pain\n- lait\nEt voilà.", LiveRewrite.join(listOf("Courses :\n- pain", "- lait", "Et voilà.")))
        assertEquals("", LiveRewrite.join(emptyList()))
    }
}
