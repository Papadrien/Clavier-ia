package fr.papadrien.clavier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardControllerTest {

    private fun key(id: String, layout: KeyboardLayout = Keyboards.letters): Key =
        layout.rows.flatten().first { it.id == id }

    @Test
    fun `taper une lettre envoie la minuscule et ne lance pas d action`() {
        val controller = KeyboardController()
        val result = controller.onKey(key("letter_a"))

        assertEquals("a", result.commit)
        assertNull(result.commit?.takeIf { it.length == 0 }, "commit doit être non vide")
        assertEquals(0, result.deleteBefore)
        assertFalse(result.isEnter)
        assertFalse(result.newState.isShifted)
        assertEquals(LayoutId.LETTERS, result.newState.activeLayout)
    }

    @Test
    fun `la touche maj active la majuscule pour une seule lettre`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))
        assertTrue(controller.state.isShifted)

        val result = controller.onKey(key("letter_z"))
        assertEquals("Z", result.commit)
        assertFalse(result.newState.isShifted)
    }

    @Test
    fun `appuyer deux fois sur maj re-desactive la majuscule`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))
        val result = controller.onKey(key("shift"))

        assertFalse(result.newState.isShifted)
    }

    @Test
    fun `un chiffre n est pas affecte par la majuscule mais la reinitialise`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))

        val result = controller.onKey(key("digit_3", Keyboards.symbols))
        assertEquals("3", result.commit)
        assertFalse(result.newState.isShifted)
    }

    @Test
    fun `la touche espace commit un espace`() {
        val controller = KeyboardController()
        val result = controller.onKey(key("space"))

        assertEquals(" ", result.commit)
    }

    @Test
    fun `la touche effacer signale la suppression d un caractere`() {
        val controller = KeyboardController()
        val result = controller.onKey(key("backspace"))

        assertEquals(1, result.deleteBefore)
        assertNull(result.commit)
        assertFalse(result.isEnter)
        assertEquals(LayoutId.LETTERS, result.newState.activeLayout)
    }

    @Test
    fun `la touche entree signale une action entree`() {
        val controller = KeyboardController()
        val result = controller.onKey(key("enter"))

        assertTrue(result.isEnter)
        assertNull(result.commit)
        assertEquals(0, result.deleteBefore)
    }

    @Test
    fun `la bascule alterne entre lettres et symboles`() {
        val controller = KeyboardController()
        assertEquals(LayoutId.LETTERS, controller.state.activeLayout)

        controller.onKey(key("toggle"))
        assertEquals(LayoutId.SYMBOLS, controller.state.activeLayout)

        val result = controller.onKey(key("toggle", Keyboards.symbols))
        assertEquals(LayoutId.LETTERS, result.newState.activeLayout)
    }

    @Test
    fun `les ponctuations sont tappables directement`() {
        val controller = KeyboardController()
        assertEquals(",", controller.onKey(key("comma")).commit)
        assertEquals(".", controller.onKey(key("period")).commit)
        assertEquals("?", controller.onKey(key("qmark")).commit)
        assertEquals("!", controller.onKey(key("exclam")).commit)
    }

    @Test
    fun `la barre espace et la ponctuation reinitialisent la majuscule`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))

        val result = controller.onKey(key("space"))
        assertFalse(result.newState.isShifted)

        controller.onKey(key("shift"))
        val result2 = controller.onKey(key("period"))
        assertFalse(result2.newState.isShifted)
    }

    @Test
    fun `reset remet le clavier dans l etat initial`() {
        val controller = KeyboardController()
        controller.onKey(key("toggle"))
        controller.onKey(key("shift"))
        controller.reset()

        assertEquals(LayoutId.LETTERS, controller.state.activeLayout)
        assertFalse(controller.state.isShifted)
    }
}