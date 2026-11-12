package fr.junade.taipo

import fr.junade.taipo.testing.testContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Audit UX-10 et TEST-01 : le nom que TalkBack lit pour la touche Entrée suit l'action du champ. */
@RunWith(RobolectricTestRunner::class)
class KeySpeechTest {

    private val enter = Key("enter", "", KeyAction.Enter)

    private fun spoken(icon: EnterAction.Icon) = KeySpeech(testContext).describe(enter, LayoutId.LETTERS, false, false, icon)

    @Test
    fun `nom de la touche Entree en anglais`() {
        assertEquals("Enter", spoken(EnterAction.Icon.ENTER))
        assertEquals("Search", spoken(EnterAction.Icon.SEARCH))
        assertEquals("Send", spoken(EnterAction.Icon.SEND))
        assertEquals("Next", spoken(EnterAction.Icon.NEXT))
        assertEquals("Done", spoken(EnterAction.Icon.DONE))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `nom de la touche Entree en francais`() {
        assertEquals("Entrée", spoken(EnterAction.Icon.ENTER))
        assertEquals("Rechercher", spoken(EnterAction.Icon.SEARCH))
        assertEquals("Envoyer", spoken(EnterAction.Icon.SEND))
    }
}
