package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceCorrectionTest {

    private fun sentences(text: String) = SentenceSplitter.split(text).map { text.substring(it.start, it.endExclusive) }

    private fun blocks(text: String, memory: CorrectedSentenceMemory) =
        CorrectionPlanner.blocksToCorrect(text, memory::contains).map { text.substring(it.start, it.endExclusive) }

    @Test
    fun `le texte est decoupe en phrases`() {
        assertEquals(
            listOf("Bonjour à tous.", "Comment allez-vous ?", "Très bien !"),
            sentences("Bonjour à tous. Comment allez-vous ? Très bien !"),
        )
    }

    @Test
    fun `un retour a la ligne termine une phrase`() {
        assertEquals(listOf("Salut", "Ça va"), sentences("Salut\nÇa va"))
    }

    @Test
    fun `les points au milieu d un mot ou d un nombre ne coupent pas`() {
        assertEquals(listOf("Pi vaut 3.14 sur www.site.fr."), sentences("Pi vaut 3.14 sur www.site.fr."))
    }

    @Test
    fun `une phrase sans ponctuation finale est conservee`() {
        assertEquals(listOf("Première phrase.", "deuxième sans point"), sentences("Première phrase. deuxième sans point"))
    }

    @Test
    fun `les guillemets fermants restent dans la phrase`() {
        assertEquals(listOf("Il dit \"oui.\"", "Puis il part."), sentences("Il dit \"oui.\" Puis il part."))
    }

    @Test
    fun `un texte vide ou blanc n a aucune phrase`() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.split("  \n ").isEmpty())
    }

    @Test
    fun `sans memoire tout le texte est a corriger en une seule zone`() {
        val text = "Bonjour. Je vais a la plage."
        assertEquals(listOf(text), blocks(text, CorrectedSentenceMemory()))
    }

    @Test
    fun `les phrases deja corrigees ne sont pas renvoyees`() {
        val memory = CorrectedSentenceMemory()
        memory.remember("Bonjour à tous. Je vais à la plage.")
        val text = "Bonjour à tous. Je vais à la plage. Il fait beau"
        assertEquals(listOf("Il fait beau"), blocks(text, memory))
    }

    @Test
    fun `une phrase ajoutee est corrigee`() {
        val memory = CorrectedSentenceMemory()
        memory.remember("Première phrase.")
        assertEquals(listOf("Nouvelle phrase."), blocks("Première phrase. Nouvelle phrase.", memory))
    }

    @Test
    fun `un mot modifie ou ajoute dans une phrase renvoie toute la phrase`() {
        val memory = CorrectedSentenceMemory()
        memory.remember("Je vais à la plage. Il fait beau.")
        assertEquals(
            listOf("Je vais à la grande plage."),
            blocks("Je vais à la grande plage. Il fait beau.", memory),
        )
        assertEquals(
            listOf("Je vais a la plage."),
            blocks("Je vais a la plage. Il fait beau.", memory),
        )
    }

    @Test
    fun `des phrases a corriger separees par une phrase corrigee font deux zones`() {
        val memory = CorrectedSentenceMemory()
        memory.remember("Deuxième.")
        assertEquals(listOf("Première.", "Troisième."), blocks("Première. Deuxième. Troisième.", memory))
    }

    @Test
    fun `des phrases a corriger consecutives forment une seule zone`() {
        val memory = CorrectedSentenceMemory()
        memory.remember("Première.")
        assertEquals(listOf("Deuxième. Troisième."), blocks("Première. Deuxième. Troisième.", memory))
    }

    @Test
    fun `des paragraphes a corriger ne sont jamais regroupes dans une meme zone`() {
        val text = "Premier paragraphe. Suite du premier.\n\nSecond paragraphe."
        assertEquals(
            listOf("Premier paragraphe. Suite du premier.", "Second paragraphe."),
            blocks(text, CorrectedSentenceMemory()),
        )
    }

    @Test
    fun `un simple retour a la ligne separe aussi les zones`() {
        assertEquals(listOf("Salut", "Ça va"), blocks("Salut\nÇa va", CorrectedSentenceMemory()))
    }

    @Test
    fun `les zones separees par un retour a la ligne gardent leurs positions`() {
        val text = "Un.\n\nDeux."
        val found = CorrectionPlanner.blocksToCorrect(text) { false }
        assertEquals(listOf(TextBlock(0, 3), TextBlock(5, 10)), found)
    }

    @Test
    fun `tout est deja corrige donne aucune zone`() {
        val memory = CorrectedSentenceMemory()
        memory.remember("A. B.")
        assertTrue(blocks("A. B.", memory).isEmpty())
    }

    @Test
    fun `les espaces et retours a la ligne n empechent pas la reconnaissance`() {
        val memory = CorrectedSentenceMemory()
        memory.remember("Je vais à la plage.")
        assertTrue(memory.contains("Je  vais à\nla plage."))
    }

    @Test
    fun `la memoire oublie les phrases les plus anciennes au dela de sa capacite`() {
        val memory = CorrectedSentenceMemory(capacity = 2)
        memory.remember("Un. Deux. Trois.")
        assertFalse(memory.contains("Un."))
        assertTrue(memory.contains("Deux."))
        assertTrue(memory.contains("Trois."))
    }
}
