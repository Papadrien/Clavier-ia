package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Les deux états de la barre du haut (normale / saisie), le menu de gauche (robot) et le panneau Smart Clipboard. */
class SuggestionZoneStateTest {

    private fun typing(): SuggestionZoneState = SuggestionZoneState().apply { onFieldTextChanged(true) }

    @Test
    fun `champ vide la barre est normale et sans robot`() {
        val state = SuggestionZoneState()
        assertEquals(BarMode.NORMAL, state.mode)
        assertFalse(state.menuButtonVisible)
        assertFalse(state.menuOpen)
    }

    @Test
    fun `des qu il y a du texte la barre passe en saisie avec le robot`() {
        val state = typing()
        assertEquals(BarMode.TYPING, state.mode)
        assertTrue(state.menuButtonVisible)
        assertFalse(state.menuOpen)
    }

    @Test
    fun `le robot ouvre puis ferme le menu pendant la saisie`() {
        val state = typing()
        state.toggleMenu()
        assertTrue(state.menuOpen)
        state.toggleMenu()
        assertFalse(state.menuOpen)
    }

    @Test
    fun `le robot est sans effet dans la barre normale`() {
        val state = SuggestionZoneState()
        state.toggleMenu()
        assertFalse(state.menuOpen)
        state.onFieldTextChanged(true)
        assertFalse(state.menuOpen) // le toucher ignoré ne reste pas en attente
    }

    @Test
    fun `la frappe referme le menu`() {
        val state = typing()
        state.toggleMenu()
        state.onTyping()
        assertFalse(state.menuOpen)
        assertEquals(BarMode.TYPING, state.mode)
    }

    @Test
    fun `apres la frappe le menu peut etre rouvert`() {
        val state = typing()
        state.toggleMenu()
        state.onTyping()
        state.toggleMenu()
        assertTrue(state.menuOpen)
    }

    @Test
    fun `un champ vide ramene la barre normale et referme le menu`() {
        val state = typing()
        state.toggleMenu()
        state.onFieldTextChanged(false)
        assertEquals(BarMode.NORMAL, state.mode)
        assertFalse(state.menuOpen)
        state.onFieldTextChanged(true)
        assertFalse(state.menuOpen) // le menu ne revient pas tout seul
    }

    @Test
    fun `un texte qui reste present ne referme pas le menu`() {
        val state = typing()
        state.toggleMenu()
        state.onFieldTextChanged(true)
        assertTrue(state.menuOpen)
    }

    @Test
    fun `la puce de collage ne change pas l etat de la barre`() {
        val state = SuggestionZoneState()
        state.setPasteAvailable(true)
        assertEquals(BarMode.NORMAL, state.mode)
        state.onFieldTextChanged(true)
        assertEquals(BarMode.TYPING, state.mode)
        assertTrue(state.pasteAvailable) // elle reste proposée, et revient quand le champ est vide
    }

    @Test
    fun `l auto-remplissage en ligne rend la barre de saisie meme sur un champ vide`() {
        val state = SuggestionZoneState()
        state.setInlineAvailable(true)
        assertEquals(BarMode.TYPING, state.mode)
        assertTrue(state.menuButtonVisible)
        state.setInlineAvailable(false)
        assertEquals(BarMode.NORMAL, state.mode)
    }

    @Test
    fun `la fin de l auto-remplissage sur un champ vide referme le menu`() {
        val state = SuggestionZoneState()
        state.setInlineAvailable(true)
        state.toggleMenu()
        assertTrue(state.menuOpen)
        state.setInlineAvailable(false)
        assertFalse(state.menuOpen)
    }

    @Test
    fun `panneau Smart Clipboard ouvert depuis la barre normale la barre reste normale`() {
        val state = SuggestionZoneState()
        state.setClipboardPanelOpen(true)
        state.onFieldTextChanged(true) // texte collé depuis le panneau
        assertEquals(BarMode.NORMAL, state.mode)
        state.setClipboardPanelOpen(false)
        assertEquals(BarMode.TYPING, state.mode) // la barre suit de nouveau l'état réel
    }

    @Test
    fun `panneau Smart Clipboard ouvert depuis le menu la barre garde le menu ouvert`() {
        val state = typing()
        state.toggleMenu()
        state.setClipboardPanelOpen(true)
        state.onTyping() // la frappe referme le menu réel, pas l'affichage figé
        assertTrue(state.menuOpen)
        assertEquals(BarMode.TYPING, state.mode)
        state.setClipboardPanelOpen(false)
        assertFalse(state.menuOpen)
    }

    @Test
    fun `panneau ferme les changements reprennent`() {
        val state = typing()
        state.setClipboardPanelOpen(true)
        state.onFieldTextChanged(false)
        assertEquals(BarMode.TYPING, state.mode)
        state.setClipboardPanelOpen(false)
        assertEquals(BarMode.NORMAL, state.mode)
    }
}
