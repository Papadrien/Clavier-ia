package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatTranscriptTest {

    @Test
    fun `sans historique le prompt est envoye tel quel`() {
        assertEquals("Écris un mot doux", ChatTranscript.build(emptyList(), "Écris un mot doux"))
    }

    @Test
    fun `l historique precede le nouveau prompt dans l ordre`() {
        val history = listOf(
            ChatExchange("premier prompt", "première réponse"),
            ChatExchange("second prompt", "seconde réponse"),
        )
        val result = ChatTranscript.build(history, "troisième prompt")

        val indexes = listOf(
            "premier prompt", "première réponse", "second prompt", "seconde réponse", "troisième prompt",
        ).map { result.indexOf(it) }
        assertTrue(indexes.all { it >= 0 })
        assertEquals(indexes.sorted(), indexes)
    }

    @Test
    fun `le nouveau prompt termine le texte`() {
        val result = ChatTranscript.build(listOf(ChatExchange("a", "b")), "suite")
        assertTrue(result.endsWith("suite"))
    }

    @Test
    fun `une reponse partielle vide ou multiligne est conservee telle quelle`() {
        val history = listOf(
            ChatExchange("a", ""),
            ChatExchange("b", "ligne 1\nligne 2"),
        )
        val result = ChatTranscript.build(history, "c")
        assertTrue(result.contains("Assistant : \n"))
        assertTrue(result.contains("ligne 1\nligne 2"))
    }
}
