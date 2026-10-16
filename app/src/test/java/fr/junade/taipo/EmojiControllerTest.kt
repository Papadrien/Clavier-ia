package fr.junade.taipo

import fr.junade.taipo.EmojiController.Companion.needsSpaceBefore
import fr.junade.taipo.EmojiController.Companion.recentBarState
import fr.junade.taipo.EmojiController.RecentBarState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Audit 2.1 : règles pures des emojis (barre des récents, espace avant l'emoji suggéré), reprises telles quelles de TaipoIme. */
class EmojiControllerTest {

    private fun state(
        promptActive: Boolean = false,
        messagingField: Boolean = true,
        hasRecents: Boolean = true,
        emojiPanelVisible: Boolean = false,
        clipboardPanelVisible: Boolean = false,
    ) = recentBarState(promptActive, messagingField, hasRecents, emojiPanelVisible, clipboardPanelVisible)

    @Test
    fun `barre visible dans un champ de messagerie avec des recents`() {
        assertEquals(RecentBarState.VISIBLE, state())
    }

    @Test
    fun `barre absente hors messagerie`() {
        assertEquals(RecentBarState.GONE, state(messagingField = false))
    }

    @Test
    fun `barre absente sans recents`() {
        assertEquals(RecentBarState.GONE, state(hasRecents = false))
    }

    @Test
    fun `barre absente en mode prompt quelle que soit la regle habituelle`() {
        assertEquals(RecentBarState.GONE, state(promptActive = true))
    }

    @Test
    fun `panneau emoji ouvert la barre garde sa place`() {
        assertEquals(RecentBarState.INVISIBLE, state(emojiPanelVisible = true))
    }

    @Test
    fun `panneau presse-papiers ouvert la barre garde sa place`() {
        assertEquals(RecentBarState.INVISIBLE, state(clipboardPanelVisible = true))
    }

    @Test
    fun `une espace precede l'emoji apres une lettre ou un chiffre`() {
        assertTrue(needsSpaceBefore('a'))
        assertTrue(needsSpaceBefore('É'))
        assertTrue(needsSpaceBefore('7'))
    }

    @Test
    fun `pas d'espace apres une espace une ponctuation ou en debut de champ`() {
        assertFalse(needsSpaceBefore(' '))
        assertFalse(needsSpaceBefore('.'))
        assertFalse(needsSpaceBefore('\n'))
        assertFalse(needsSpaceBefore(null))
    }
}
