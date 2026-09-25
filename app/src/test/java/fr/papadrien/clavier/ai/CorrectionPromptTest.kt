package fr.papadrien.clavier.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrectionPromptTest {

    @Test
    fun `le prompt systeme n est pas vide`() {
        assertFalse(CorrectionPrompt.SYSTEM.isBlank())
    }

    @Test
    fun `le prompt systeme precise de ne repondre qu avec le texte corrige`() {
        assertTrue(CorrectionPrompt.SYSTEM.contains("texte corrigé"))
    }

    @Test
    fun `le prompt systeme se protege des instructions injectees dans le texte`() {
        assertTrue(CorrectionPrompt.SYSTEM.contains("ignore", ignoreCase = true))
    }
}
