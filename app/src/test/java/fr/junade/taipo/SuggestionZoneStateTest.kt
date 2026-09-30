package fr.junade.taipo

import fr.junade.taipo.SuggestionZoneState.Zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 2.1 : bouton Smart Clipboard visible « avant saisie », caché dans le menu dès qu'il y a du texte. */
class SuggestionZoneStateTest {

    @Test
    fun `champ vide (avant saisie) le bouton Smart Clipboard est visible et pas le menu`() {
        val state = SuggestionZoneState()
        assertEquals(Zone.CLIPBOARD, state.zone)
        assertFalse(state.menuButtonVisible)
    }

    @Test
    fun `des qu il y a du texte le bouton est cache, la zone montre les mots et le menu apparait`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        assertEquals(Zone.WORDS, state.zone)
        assertTrue(state.menuButtonVisible)
    }

    @Test
    fun `le menu redonne acces au bouton pendant la saisie et se referme en le retouchant`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        assertEquals(Zone.CLIPBOARD, state.zone)
        assertTrue(state.menuExpanded)
        state.toggleMenu()
        assertEquals(Zone.WORDS, state.zone)
        assertFalse(state.menuExpanded)
    }

    @Test
    fun `le menu se referme des le debut de la frappe`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        state.onTyping()
        assertEquals(Zone.WORDS, state.zone)
    }

    @Test
    fun `un champ vide ne garde jamais le menu ouvert, meme apres un nouveau texte`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        state.onFieldTextChanged(false)
        assertFalse(state.menuExpanded)
        state.onFieldTextChanged(true)
        assertEquals(Zone.WORDS, state.zone)
    }

    @Test
    fun `le menu est sans effet sur un champ vide`() {
        val state = SuggestionZoneState()
        state.toggleMenu()
        assertFalse(state.menuExpanded)
        assertEquals(Zone.CLIPBOARD, state.zone)
    }

    @Test
    fun `un texte qui reste present ne referme pas le menu`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        state.onFieldTextChanged(true) // ex. déplacement du curseur : le texte est toujours là
        assertEquals(Zone.CLIPBOARD, state.zone)
    }
}

/** Story 2.2 : la puce de collage prend la place des mots / du bouton, le menu reste accessible. */
class SuggestionZoneStatePasteTest {

    @Test
    fun `aucune puce par defaut`() {
        assertFalse(SuggestionZoneState().pasteAvailable)
    }

    @Test
    fun `champ vide avec un collage propose la puce remplace le bouton et le menu est visible`() {
        val state = SuggestionZoneState()
        state.setPasteAvailable(true)
        assertEquals(Zone.PASTE, state.zone)
        assertTrue(state.menuButtonVisible)
    }

    @Test
    fun `pendant la saisie la puce prend la place des mots`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.setPasteAvailable(true)
        assertEquals(Zone.PASTE, state.zone)
    }

    @Test
    fun `le menu donne acces au bouton a la place de la puce et la puce revient en le retouchant`() {
        val state = SuggestionZoneState()
        state.setPasteAvailable(true)
        state.toggleMenu()
        assertEquals(Zone.CLIPBOARD, state.zone)
        state.toggleMenu()
        assertEquals(Zone.PASTE, state.zone)
    }

    @Test
    fun `la puce retiree sur un champ vide referme le menu et le cache`() {
        val state = SuggestionZoneState()
        state.setPasteAvailable(true)
        state.toggleMenu()
        state.setPasteAvailable(false)
        assertFalse(state.menuExpanded)
        assertFalse(state.menuButtonVisible)
        assertEquals(Zone.CLIPBOARD, state.zone)
    }

    @Test
    fun `la puce retiree pendant la saisie redonne les mots et garde le menu`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.setPasteAvailable(true)
        state.setPasteAvailable(false)
        assertEquals(Zone.WORDS, state.zone)
        assertTrue(state.menuButtonVisible)
    }

    @Test
    fun `le menu ouvert sur la puce reste ouvert si le champ se vide`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.setPasteAvailable(true)
        state.toggleMenu()
        state.onFieldTextChanged(false)
        assertEquals(Zone.CLIPBOARD, state.zone)
        assertTrue(state.menuExpanded)
    }

    @Test
    fun `le debut de la frappe referme le menu puis la puce ecartee redonne les mots`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.setPasteAvailable(true)
        state.toggleMenu()
        state.onTyping()
        state.setPasteAvailable(false)
        assertEquals(Zone.WORDS, state.zone)
    }
}

/** Story 2.4 : retour aux suggestions de mots dès la frappe, menu « ··· » refermé. */
class SuggestionZoneStateTypingTest {

    @Test
    fun `menu ouvert sur champ vide avec la puce puis frappe le menu se referme`() {
        val state = SuggestionZoneState()
        state.setPasteAvailable(true)
        state.toggleMenu()
        assertEquals(Zone.CLIPBOARD, state.zone)
        state.onTyping()
        state.setPasteAvailable(false) // la frappe écarte aussi la puce
        state.onFieldTextChanged(true) // le champ contient maintenant du texte
        assertFalse(state.menuExpanded)
        assertEquals(Zone.WORDS, state.zone)
    }

    @Test
    fun `menu ouvert avec la puce pendant la saisie puis frappe redonne les mots`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.setPasteAvailable(true)
        state.toggleMenu()
        state.onTyping()
        state.setPasteAvailable(false)
        assertEquals(Zone.WORDS, state.zone)
        assertFalse(state.menuExpanded)
    }

    @Test
    fun `apres la frappe le menu peut etre rouvert pendant la saisie`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        state.onTyping()
        state.toggleMenu()
        assertEquals(Zone.CLIPBOARD, state.zone)
    }

    @Test
    fun `sans menu ouvert la frappe ne change pas la zone`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.onTyping()
        assertEquals(Zone.WORDS, state.zone)
        val paste = SuggestionZoneState()
        paste.setPasteAvailable(true)
        paste.onTyping()
        assertEquals(Zone.PASTE, paste.zone) // c'est l'appelant qui retire la puce (état de la copie)
    }

    @Test
    fun `un champ vide apres la frappe avec le menu ouvert ne garde pas le menu sans puce`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        state.onFieldTextChanged(false) // champ vidé (toutes les lettres effacées), pas de puce
        assertFalse(state.menuExpanded)
        assertEquals(Zone.CLIPBOARD, state.zone)
    }
}

/** Vocal et Corriger sont rangés derrière le menu tant que la bande de mots est affichée. */
class SuggestionZoneStateActionsTest {

    @Test
    fun `pendant les suggestions de mots Vocal et Corriger sont caches`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        assertEquals(Zone.WORDS, state.zone)
        assertFalse(state.actionButtonsVisible(busy = false))
    }

    @Test
    fun `le menu ouvert fait reapparaitre Vocal et Corriger`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        assertTrue(state.actionButtonsVisible(busy = false))
        state.toggleMenu()
        assertFalse(state.actionButtonsVisible(busy = false))
    }

    @Test
    fun `la frappe referme le menu et range de nouveau les boutons`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.toggleMenu()
        state.onTyping()
        assertFalse(state.actionButtonsVisible(busy = false))
    }

    @Test
    fun `champ vide (avant saisie) Vocal reste visible`() {
        val state = SuggestionZoneState()
        assertTrue(state.actionButtonsVisible(busy = false))
    }

    @Test
    fun `la puce de collage ne range pas Vocal et Corriger`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true)
        state.setPasteAvailable(true)
        assertEquals(Zone.PASTE, state.zone)
        assertTrue(state.actionButtonsVisible(busy = false))
    }

    @Test
    fun `une ecoute ou une correction en cours garde les boutons visibles meme pendant les mots`() {
        val state = SuggestionZoneState()
        state.onFieldTextChanged(true) // ex. texte dicté inséré : la zone repasse aux mots
        assertEquals(Zone.WORDS, state.zone)
        assertTrue(state.actionButtonsVisible(busy = true))
    }
}
