package fr.junade.taipo

import fr.junade.taipo.DeleteSwipeController.Companion.wordToDeleteBeforeCursor
import android.view.inputmethod.InputConnection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Audit 2.1 : la portion visée par le glissement de suppression (story 1.9), logique reprise telle quelle de TaipoIme. */
class DeleteSwipeControllerTest {

    @Test
    fun `texte vide`() {
        assertEquals("", wordToDeleteBeforeCursor(""))
    }

    @Test
    fun `le dernier mot est vise`() {
        assertEquals("monde", wordToDeleteBeforeCursor("bonjour monde"))
    }

    @Test
    fun `les espaces de fin sont inclus avec le mot`() {
        assertEquals("monde  ", wordToDeleteBeforeCursor("bonjour monde  "))
    }

    @Test
    fun `des espaces seuls sont vises`() {
        assertEquals("   ", wordToDeleteBeforeCursor("   "))
    }

    @Test
    fun `apres une ponctuation un seul caractere est vise`() {
        assertEquals(".", wordToDeleteBeforeCursor("fin."))
    }

    @Test
    fun `une apostrophe fait partie du mot`() {
        assertEquals("l'homme", wordToDeleteBeforeCursor("voici l'homme"))
    }

    @Test
    fun `deux glissements successifs consomment deux mots`() {
        val first = wordToDeleteBeforeCursor("un deux trois")
        val rest = "un deux trois".dropLast(first.length)
        assertEquals("trois", first)
        assertEquals("deux ", wordToDeleteBeforeCursor(rest))
    }

    /** Hôte minimal en mode prompt (aucun champ d'application) qui note les sons et retours haptiques. */
    private class RecordingHost(private val before: String) : DeleteSwipeController.Host {
        val sounds = mutableListOf<KeySound>()
        var cursorHaptics = 0
        var deleted = 0

        override fun promptActive() = true
        override fun inputConnection(): InputConnection? = null
        override fun lastSelectionStart() = -1
        override fun lastSelectionEnd() = -1
        override fun promptCursor() = before.length
        override fun promptTextBeforeCursor() = before
        override fun promptDeleteBefore(count: Int) {
            deleted = count
        }
        override fun haptic() = Unit
        override fun hapticCursorMove() {
            cursorHaptics++
        }
        override fun playKeySound(sound: KeySound) {
            sounds += sound
        }
        override fun clearHighlight() = Unit
        override fun clearPendingAutocorrection() = Unit
        override fun onUserTyped() = Unit
        override fun recordUndoDeletion(deleted: String, anchor: String, before: String, mergeable: Boolean, offsetProvider: () -> Int) = Unit
        override fun syncAutoCapitalization() = Unit
        override fun applyState() = Unit
        override fun updateCorrectionBarVisibility() = Unit
    }

    @Test
    fun `le glissement joue un cran par changement de zone puis la gomme a la suppression`() {
        val host = RecordingHost("bonjour tout le monde")
        val controller = DeleteSwipeController(host)
        controller.onUpdate(1)
        controller.onUpdate(1) // même zone : pas de nouveau cran
        controller.onUpdate(2)
        assertEquals(listOf(KeySound.CURSOR_MOVE, KeySound.CURSOR_MOVE), host.sounds)
        assertEquals(2, host.cursorHaptics)
        controller.onRelease()
        assertEquals(listOf(KeySound.CURSOR_MOVE, KeySound.CURSOR_MOVE, KeySound.BACKSPACE), host.sounds)
        assertEquals("le monde".length, host.deleted) // « monde » puis « le » et son espace
    }

    @Test
    fun `un glissement ramene au depart ne joue pas la gomme`() {
        val host = RecordingHost("bonjour tout le monde")
        val controller = DeleteSwipeController(host)
        controller.onUpdate(1)
        controller.onUpdate(0)
        controller.onRelease()
        assertEquals(listOf(KeySound.CURSOR_MOVE, KeySound.CURSOR_MOVE), host.sounds)
        assertEquals(0, host.deleted)
    }
}
