package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SystemPromptEditTest {

    private val default = "Prompt par défaut."

    @Test
    fun `un texte vide ou blanc revient au defaut`() {
        assertNull(SystemPromptEdit.toStore("", default))
        assertNull(SystemPromptEdit.toStore("  \n ", default))
    }

    @Test
    fun `un texte identique au defaut ne cree pas de prompt personnalise`() {
        assertNull(SystemPromptEdit.toStore(default, default))
        // Espaces ou retours à la ligne autour : toujours le défaut.
        assertNull(SystemPromptEdit.toStore("\n$default \n", default))
    }

    @Test
    fun `un texte different est enregistre tel quel`() {
        val edited = "Prompt modifié.\nDeux lignes."
        assertEquals(edited, SystemPromptEdit.toStore(edited, default))
    }

    @Test
    fun `une simple variation de casse est un prompt personnalise`() {
        assertEquals("prompt par défaut.", SystemPromptEdit.toStore("prompt par défaut.", default))
    }
}
