package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 1.18 : la majuscule automatique est coupée dans les champs e-mail, URL, mot de passe et numériques. */
class KeyboardControllerFieldTest {

    @Test
    fun `majuscule automatique active par defaut`() {
        val controller = KeyboardController()
        assertTrue(controller.autoCapitalization)
        controller.applyTextContext("")
        assertTrue(controller.state.isShifted)
    }

    @Test
    fun `desactivee, un champ vide ou une fin de phrase ne met pas en majuscule`() {
        val controller = KeyboardController()
        controller.setAutoCapitalization(false)
        controller.applyTextContext("")
        assertFalse(controller.state.isShifted)
        controller.applyTextContext("Bonjour. ")
        assertFalse(controller.state.isShifted)
    }

    @Test
    fun `desactiver retire une majuscule deja en attente`() {
        val controller = KeyboardController()
        controller.applyTextContext("")
        assertTrue(controller.state.isShifted)
        controller.setAutoCapitalization(false)
        assertFalse(controller.state.isShifted)
    }

    @Test
    fun `le choix manuel de Maj reste possible quand la majuscule automatique est coupee`() {
        val controller = KeyboardController()
        controller.setAutoCapitalization(false)
        val shift = Keyboards.letters.rows.flatten().first { it.id == "shift" }
        controller.onKey(shift)
        assertTrue(controller.state.isShifted)
    }

    @Test
    fun `reinitialiser le controleur ne reactive pas la majuscule automatique`() {
        val controller = KeyboardController()
        controller.setAutoCapitalization(false)
        controller.reset()
        assertFalse(controller.autoCapitalization)
    }

    @Test
    fun `reactivee, la majuscule automatique reprend`() {
        val controller = KeyboardController()
        controller.setAutoCapitalization(false)
        controller.setAutoCapitalization(true)
        controller.applyTextContext("")
        assertTrue(controller.state.isShifted)
    }
}
