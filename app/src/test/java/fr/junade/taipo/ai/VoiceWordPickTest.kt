package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceWordPickTest {

    private var clock = 1_000L
    private val pick = VoiceWordPick { clock }

    private fun click(old: Int, new: Int) =
        pick.onCursorMoved(old, new, collapsed = true, atDictationEnd = false, typedRecently = false)

    @Test
    fun `un toucher dans le texte active le mot touche`() {
        click(old = 20, new = 8)
        assertTrue(pick.active)
    }

    @Test
    fun `l'echo d'une insertion de la dictee n'active rien`() {
        pick.onCursorMoved(10, 20, collapsed = true, atDictationEnd = true, typedRecently = false)
        assertFalse(pick.active)
    }

    @Test
    fun `une selection, un curseur immobile ou la frappe au clavier ne sont pas des touchers`() {
        pick.onCursorMoved(20, 8, collapsed = false, atDictationEnd = false, typedRecently = false)
        assertFalse(pick.active)
        click(old = 8, new = 8)
        assertFalse(pick.active)
        pick.onCursorMoved(20, 21, collapsed = true, atDictationEnd = false, typedRecently = true)
        assertFalse(pick.active)
    }

    @Test
    fun `la dictee qui reprend la main desactive le mot touche`() {
        click(20, 8)
        pick.onCursorMoved(8, 15, collapsed = true, atDictationEnd = true, typedRecently = false)
        assertFalse(pick.active)
        assertNull(pick.onWordReplaced(cursorBefore = 8, lengthDelta = 1))
    }

    @Test
    fun `remplacer un mot situe avant le point de reprise decale le curseur de retour`() {
        click(old = 20, new = 8)
        // « ses » (3 lettres + espace) devient « c'est » : 3 caracteres de plus.
        assertEquals(23, pick.onWordReplaced(cursorBefore = 8, lengthDelta = 3))
        assertFalse(pick.active)
    }

    @Test
    fun `le point de reprise reste celui du premier toucher`() {
        click(old = 20, new = 8)
        click(old = 8, new = 12)
        assertEquals(20, pick.onWordReplaced(cursorBefore = 12, lengthDelta = 0))
    }

    @Test
    fun `un mot situe apres le point de reprise ne le decale pas`() {
        click(old = 5, new = 30)
        assertEquals(5, pick.onWordReplaced(cursorBefore = 30, lengthDelta = 4))
    }

    @Test
    fun `l'echo qui suit un remplacement est ignore un instant`() {
        click(20, 8)
        pick.onWordReplaced(8, 2)
        click(8, 13) // écho de la modification
        assertFalse(pick.active)
        clock += 500
        click(22, 3) // vrai toucher, plus tard
        assertTrue(pick.active)
    }

    @Test
    fun `reset remet tout a zero`() {
        click(20, 8)
        pick.reset()
        assertFalse(pick.active)
        assertNull(pick.onWordReplaced(8, 1))
    }
}
