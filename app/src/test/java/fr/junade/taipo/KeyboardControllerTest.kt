package fr.junade.taipo

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
    fun `double espace apres un mot remplace l espace par un point espace`() {
        val result = KeyboardController().onKey(key("space"), "bonjour ")
        assertEquals(". ", result.commit)
        assertEquals(1, result.deleteBefore)
    }

    @Test
    fun `double espace sans mot avant ou apres ponctuation ne change rien`() {
        listOf("", " ", "mot  ", "fin. ", "ok, ").forEach { text ->
            val result = KeyboardController().onKey(key("space"), text)
            assertEquals(" ", result.commit, "texte: '$text'")
            assertEquals(0, result.deleteBefore)
        }
        assertEquals(" ", KeyboardController().onKey(key("space")).commit)
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
        assertEquals("'", controller.onKey(key("apostrophe")).commit)
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

    @Test
    fun `la langue par defaut est le francais`() {
        val controller = KeyboardController()
        assertEquals(KeyboardLanguage.FR, controller.state.language)
    }

    @Test
    fun `setLanguage change la langue active et reinitialise la majuscule`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))

        controller.setLanguage(KeyboardLanguage.EN)

        assertEquals(KeyboardLanguage.EN, controller.state.language)
        assertFalse(controller.state.isShifted)
    }

    @Test
    fun `reset ne reinitialise pas la langue active`() {
        val controller = KeyboardController()
        controller.setLanguage(KeyboardLanguage.EN)
        controller.reset()

        assertEquals(KeyboardLanguage.EN, controller.state.language)
    }

    // Story 1.2 : majuscule automatique en début de phrase/après ponctuation, non désactivable.

    @Test
    fun `shouldAutoCapitalize est vrai si le champ est vide`() {
        assertTrue(KeyboardController.shouldAutoCapitalize(""))
    }

    @Test
    fun `shouldAutoCapitalize est vrai apres un point suivi d espaces`() {
        assertTrue(KeyboardController.shouldAutoCapitalize("Bonjour."))
        assertTrue(KeyboardController.shouldAutoCapitalize("Bonjour. "))
        assertTrue(KeyboardController.shouldAutoCapitalize("Bonjour.   "))
    }

    @Test
    fun `shouldAutoCapitalize est vrai apres un point d exclamation ou d interrogation`() {
        assertTrue(KeyboardController.shouldAutoCapitalize("Salut ! "))
        assertTrue(KeyboardController.shouldAutoCapitalize("Ça va ? "))
    }

    @Test
    fun `shouldAutoCapitalize est vrai juste apres un retour a la ligne`() {
        assertTrue(KeyboardController.shouldAutoCapitalize("Bonjour\n"))
    }

    @Test
    fun `shouldAutoCapitalize est faux au milieu d une phrase`() {
        assertFalse(KeyboardController.shouldAutoCapitalize("Bonjour"))
        assertFalse(KeyboardController.shouldAutoCapitalize("Bonjour "))
        assertFalse(KeyboardController.shouldAutoCapitalize("Bonjour, comment vas"))
    }

    @Test
    fun `shouldAutoCapitalize est faux apres une virgule`() {
        assertFalse(KeyboardController.shouldAutoCapitalize("Bonjour,"))
        assertFalse(KeyboardController.shouldAutoCapitalize("Bonjour, "))
    }

    @Test
    fun `applyTextContext active la majuscule en debut de champ`() {
        val controller = KeyboardController()
        controller.applyTextContext("")

        val result = controller.onKey(key("letter_a"))
        assertEquals("A", result.commit)
    }

    @Test
    fun `applyTextContext active la majuscule apres une ponctuation de fin de phrase`() {
        val controller = KeyboardController()
        controller.applyTextContext("Bonjour. ")

        val result = controller.onKey(key("letter_a"))
        assertEquals("A", result.commit)
    }

    @Test
    fun `applyTextContext desactive la majuscule au milieu d une phrase`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))
        controller.applyTextContext("Bonjour ")

        val result = controller.onKey(key("letter_a"))
        assertEquals("a", result.commit)
    }

    @Test
    fun `une majuscule automatique reste annulable manuellement via la touche maj`() {
        // Non desactivable dans les parametres, mais l'utilisateur garde la main
        // touche par touche via le bouton Maj (comme sur Gboard).
        val controller = KeyboardController()
        controller.applyTextContext("")
        controller.onKey(key("shift"))

        val result = controller.onKey(key("letter_a"))
        assertEquals("a", result.commit)
    }

    @Test
    fun `la touche emoji ne change ni le texte ni l etat du clavier`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))
        val before = controller.state

        val result = controller.onKey(key("emoji"))

        assertNull(result.commit)
        assertEquals(0, result.deleteBefore)
        assertFalse(result.isEnter)
        assertEquals(before, result.newState)
        assertEquals(before, controller.state)
    }

    @Test
    fun `double appui rapide sur maj verrouille les majuscules`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"), uptimeMillis = 1_000)
        controller.onKey(key("shift"), uptimeMillis = 1_200)

        assertTrue(controller.state.isCapsLock)
        assertTrue(controller.state.isShifted)
    }

    @Test
    fun `deux appuis sur maj trop espaces ne verrouillent pas`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"), uptimeMillis = 1_000)
        controller.onKey(key("shift"), uptimeMillis = 1_000 + KeyboardController.SHIFT_DOUBLE_TAP_MS + 1)

        assertFalse(controller.state.isCapsLock)
        assertFalse(controller.state.isShifted)
    }

    @Test
    fun `sans horodatage maj ne verrouille jamais`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"))
        controller.onKey(key("shift"))

        assertFalse(controller.state.isCapsLock)
    }

    @Test
    fun `le verrouillage reste actif apres plusieurs lettres, un espace et une entree`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"), uptimeMillis = 1_000)
        controller.onKey(key("shift"), uptimeMillis = 1_100)

        assertEquals("A", controller.onKey(key("letter_a")).commit)
        assertEquals("B", controller.onKey(key("letter_b")).commit)
        controller.onKey(key("space"))
        controller.onKey(key("enter"))
        assertEquals("C", controller.onKey(key("letter_c")).commit)
        assertTrue(controller.state.isCapsLock)
        assertTrue(controller.state.isShifted)
    }

    @Test
    fun `un appui sur maj desactive le verrouillage`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"), uptimeMillis = 1_000)
        controller.onKey(key("shift"), uptimeMillis = 1_100)
        controller.onKey(key("shift"), uptimeMillis = 5_000)

        assertFalse(controller.state.isCapsLock)
        assertFalse(controller.state.isShifted)
        assertEquals("a", controller.onKey(key("letter_a")).commit)
    }

    @Test
    fun `une lettre entre deux appuis sur maj annule le double appui`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"), uptimeMillis = 1_000)
        controller.onKey(key("letter_a"), uptimeMillis = 1_050)
        controller.onKey(key("shift"), uptimeMillis = 1_100)

        assertFalse(controller.state.isCapsLock)
        assertTrue(controller.state.isShifted)
    }

    @Test
    fun `la majuscule automatique ne desactive pas le verrouillage`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"), uptimeMillis = 1_000)
        controller.onKey(key("shift"), uptimeMillis = 1_100)

        controller.applyTextContext("bonjour")
        controller.setAutoCapitalization(false)

        assertTrue(controller.state.isCapsLock)
        assertTrue(controller.state.isShifted)
    }

    @Test
    fun `changer de champ ou de langue desactive le verrouillage`() {
        val controller = KeyboardController()
        controller.onKey(key("shift"), uptimeMillis = 1_000)
        controller.onKey(key("shift"), uptimeMillis = 1_100)
        controller.reset()
        assertFalse(controller.state.isCapsLock)

        controller.onKey(key("shift"), uptimeMillis = 2_000)
        controller.onKey(key("shift"), uptimeMillis = 2_100)
        controller.setLanguage(KeyboardLanguage.EN)
        assertFalse(controller.state.isCapsLock)
        assertFalse(controller.state.isShifted)
    }
}
