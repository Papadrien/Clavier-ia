package fr.junade.taipo

import android.view.inputmethod.EditorInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Audit BUG-01 et UX-10 : ce que fait la touche Entrée selon les options du champ, et son icône. */
class EnterActionTest {

    @Test
    fun `un champ avec action execute l'action de l'editeur`() {
        listOf(
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_PREVIOUS,
        ).forEach { action -> assertEquals(EnterAction.Perform(action), EnterAction.of(action), "action $action") }
    }

    @Test
    fun `sans action ou avec NO_ENTER_ACTION, Entree passe a la ligne`() {
        assertEquals(EnterAction.Newline, EnterAction.of(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals(EnterAction.Newline, EnterAction.of(EditorInfo.IME_ACTION_NONE))
        assertEquals(EnterAction.Newline, EnterAction.of(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
    }

    @Test
    fun `une action personnalisee passe avant l'action standard`() {
        assertEquals(EnterAction.Perform(42), EnterAction.of(EditorInfo.IME_ACTION_DONE, customActionId = 42, hasCustomLabel = true))
        // Sans libellé, l'identifiant seul ne compte pas (comportement d'Android).
        assertEquals(EnterAction.Perform(EditorInfo.IME_ACTION_DONE), EnterAction.of(EditorInfo.IME_ACTION_DONE, customActionId = 42))
    }

    @Test
    fun `les autres drapeaux ne changent pas l'action`() {
        val options = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        assertEquals(EnterAction.Perform(EditorInfo.IME_ACTION_SEARCH), EnterAction.of(options))
    }

    @Test
    fun `icone de la touche Entree`() {
        assertEquals(EnterAction.Icon.ENTER, EnterAction.iconFor(EnterAction.Newline))
        assertEquals(EnterAction.Icon.SEARCH, EnterAction.iconFor(EnterAction.Perform(EditorInfo.IME_ACTION_SEARCH)))
        assertEquals(EnterAction.Icon.SEND, EnterAction.iconFor(EnterAction.Perform(EditorInfo.IME_ACTION_SEND)))
        assertEquals(EnterAction.Icon.NEXT, EnterAction.iconFor(EnterAction.Perform(EditorInfo.IME_ACTION_NEXT)))
        assertEquals(EnterAction.Icon.NEXT, EnterAction.iconFor(EnterAction.Perform(EditorInfo.IME_ACTION_GO)))
        assertEquals(EnterAction.Icon.DONE, EnterAction.iconFor(EnterAction.Perform(EditorInfo.IME_ACTION_DONE)))
        assertEquals(EnterAction.Icon.ENTER, EnterAction.iconFor(EnterAction.Perform(EditorInfo.IME_ACTION_PREVIOUS)))
        assertEquals(EnterAction.Icon.ENTER, EnterAction.iconFor(EnterAction.Perform(42)))
    }
}
