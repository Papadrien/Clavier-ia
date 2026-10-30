package fr.junade.taipo

import fr.junade.taipo.clipboard.ImageAcceptance
import fr.junade.taipo.clipboard.PasteChipChoice
import fr.junade.taipo.clipboard.ScreenshotSuggestionState
import fr.junade.taipo.clipboard.ScreenshotSuggestionState.Shot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Smart Clipboard : la capture d'écran récente proposée au collage (état, choix de la puce, types d'images acceptés). */
class ScreenshotSuggestionTest {

    private var now = 1_000_000L
    private val state = ScreenshotSuggestionState(clock = { now })
    private fun shot(id: Long = 1, takenAt: Long = now, mime: String = "image/png") = Shot(id, takenAt, mime)

    @Test
    fun `aucune capture, aucune suggestion`() {
        assertNull(state.suggestion())
        assertNull(state.expiresInMillis())
    }

    @Test
    fun `une capture recente est proposee puis expire apres 10 minutes`() {
        state.onFound(shot())
        assertEquals(shot(), state.suggestion())
        assertEquals(ScreenshotSuggestionState.EXPIRATION_MILLIS, state.expiresInMillis())
        now += ScreenshotSuggestionState.EXPIRATION_MILLIS - 1
        assertEquals(shot(), state.suggestion())
        now += 1
        assertNull(state.suggestion())
        assertNull(state.expiresInMillis())
    }

    @Test
    fun `une capture collee n'est plus proposee, une nouvelle capture l'est`() {
        state.onFound(shot(id = 1))
        state.onPasted()
        assertNull(state.suggestion())
        // Relecture de la même capture (nouvelle recherche) : toujours pas reproposée.
        state.onFound(shot(id = 1))
        assertNull(state.suggestion())
        state.onFound(shot(id = 2))
        assertEquals(2L, state.suggestion()?.id)
    }

    @Test
    fun `plus de capture trouvee retire la suggestion`() {
        state.onFound(shot())
        state.onFound(null)
        assertNull(state.suggestion())
    }

    @Test
    fun `clear retire la capture`() {
        state.onFound(shot())
        state.clear()
        assertNull(state.suggestion())
    }

    @Test
    fun `la memoire des captures collees est bornee`() {
        for (id in 1L..30L) {
            state.onFound(shot(id = id))
            state.onPasted()
        }
        // Les plus anciennes sont oubliées : la capture 1 serait de nouveau proposable.
        state.onFound(shot(id = 1))
        assertEquals(1L, state.suggestion()?.id)
        state.onFound(shot(id = 30))
        assertNull(state.suggestion())
    }

    @Test
    fun `la puce montre la plus recente entre le texte et la capture`() {
        assertEquals(PasteChipChoice.Kind.NONE, PasteChipChoice.pick(null, null))
        assertEquals(PasteChipChoice.Kind.TEXT, PasteChipChoice.pick(100L, null))
        assertEquals(PasteChipChoice.Kind.SCREENSHOT, PasteChipChoice.pick(null, 100L))
        assertEquals(PasteChipChoice.Kind.SCREENSHOT, PasteChipChoice.pick(100L, 200L))
        assertEquals(PasteChipChoice.Kind.TEXT, PasteChipChoice.pick(200L, 100L))
        assertEquals(PasteChipChoice.Kind.TEXT, PasteChipChoice.pick(100L, 100L))
    }

    @Test
    fun `un champ texte seul n'accepte pas d'image`() {
        assertFalse(ImageAcceptance.accepts(emptyList(), "image/png"))
        assertFalse(ImageAcceptance.accepts(listOf("image/gif"), "image/png"))
    }

    @Test
    fun `les types declares par le champ sont compares sans tenir compte de la casse, jokers compris`() {
        assertTrue(ImageAcceptance.accepts(listOf("image/png"), "image/png"))
        assertTrue(ImageAcceptance.accepts(listOf("image/gif", "IMAGE/PNG"), "image/png"))
        assertTrue(ImageAcceptance.accepts(listOf("image/*"), "image/jpeg"))
        assertTrue(ImageAcceptance.accepts(listOf("*/*"), "image/webp"))
        assertFalse(ImageAcceptance.accepts(listOf("text/*"), "image/png"))
        assertFalse(ImageAcceptance.accepts(listOf("image/jpeg"), "image/png"))
    }
}
