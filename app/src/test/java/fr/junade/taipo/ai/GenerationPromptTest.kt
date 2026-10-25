package fr.junade.taipo.ai

import fr.junade.taipo.KeyboardLanguage

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
    fun `la reponse suit la langue du clavier`() {
        for (language in KeyboardLanguage.entries) {
            val prompt = GenerationPrompt.withLanguage(GenerationPrompt.SYSTEM, language)
            assertTrue(prompt.startsWith(GenerationPrompt.SYSTEM.trimEnd()), "le prompt de base est conservé pour $language")
            assertTrue(prompt.contains("Always write your answer in ${GenerationPrompt.englishName(language)}"), "langue $language")
        }
        assertTrue(GenerationPrompt.withLanguage("Réponds court.", KeyboardLanguage.EN).contains("English"))
    }

    @Test
    fun `le prompt systeme ne contient pas de dollar`() {
        assertFalse(GenerationPrompt.SYSTEM.contains("$"))
    }
}
