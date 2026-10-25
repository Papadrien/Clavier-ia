package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Historique du bouton Annuler : dix actions, de la plus récente à la plus ancienne. */
class UndoHistoryTest {

    private val undone = mutableListOf<Int>()

    private fun action(id: Int, succeeds: Boolean = true) = UndoHistory.Undo {
        undone += id
        succeeds
    }

    @Test
    fun `le nombre d actions annulables est de dix`() {
        assertEquals(10, UndoHistory.MAX_ACTIONS)
    }

    @Test
    fun `un historique vide n annule rien`() {
        val history = UndoHistory()
        assertTrue(history.isEmpty)
        assertEquals(UndoHistory.Outcome.EMPTY, history.undoLast())
    }

    @Test
    fun `les actions sont annulees de la plus recente a la plus ancienne`() {
        val history = UndoHistory()
        (1..3).forEach { history.push(action(it)) }
        repeat(3) { assertEquals(UndoHistory.Outcome.DONE, history.undoLast()) }
        assertEquals(listOf(3, 2, 1), undone)
        assertTrue(history.isEmpty)
    }

    @Test
    fun `seules les dix dernieres actions sont gardees`() {
        val history = UndoHistory()
        (1..12).forEach { history.push(action(it)) }
        assertEquals(10, history.size)
        repeat(10) { assertEquals(UndoHistory.Outcome.DONE, history.undoLast()) }
        assertEquals(UndoHistory.Outcome.EMPTY, history.undoLast())
        assertEquals((12 downTo 3).toList(), undone)
    }

    @Test
    fun `une action qui echoue sort de l historique et la precedente reste annulable`() {
        val history = UndoHistory()
        history.push(action(1))
        history.push(action(2, succeeds = false))
        assertEquals(UndoHistory.Outcome.FAILED, history.undoLast())
        assertFalse(history.isEmpty)
        assertEquals(UndoHistory.Outcome.DONE, history.undoLast())
        assertEquals(listOf(2, 1), undone)
    }

    @Test
    fun `clear vide l historique sans rien annuler`() {
        val history = UndoHistory()
        history.push(action(1))
        history.clear()
        assertTrue(history.isEmpty)
        assertEquals(UndoHistory.Outcome.EMPTY, history.undoLast())
        assertTrue(undone.isEmpty())
    }

    @Test
    fun `une capacite nulle est refusee`() {
        assertThrows(IllegalArgumentException::class.java) { UndoHistory(0) }
    }
}
