package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenerationPromptTest {

    @Test
    fun `le prompt systeme n est pas vide`() {
        assertFalse(GenerationPrompt.SYSTEM.isBlank())
    }

    @Test
    fun `le prompt systeme demande des reponses courtes par defaut`() {
        assertTrue(GenerationPrompt.SYSTEM.contains("by default", ignoreCase = true))
        assertTrue(GenerationPrompt.SYSTEM.contains("briefly", ignoreCase = true))
    }

    @Test
    fun `le prompt systeme autorise une reponse longue sur demande`() {
        assertTrue(GenerationPrompt.SYSTEM.contains("détaille", ignoreCase = true))
        assertTrue(GenerationPrompt.SYSTEM.contains("long", ignoreCase = true))
    }

    @Test
    fun `le prompt systeme interdit le markdown`() {
        assertTrue(GenerationPrompt.SYSTEM.contains("Markdown"))
        assertTrue(GenerationPrompt.SYSTEM.contains("plain text", ignoreCase = true))
    }

    @Test
    fun `le prompt systeme ne contient pas de dollar`() {
        assertFalse(GenerationPrompt.SYSTEM.contains("$"))
    }
}
