package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Suppression annulable : regroupement des appuis successifs sur la touche retour arrière. */
class DeletionUndoTest {

    private data class Call(val text: String, val offset: Int, val anchor: String)

    private val calls = mutableListOf<Call>()

    private fun deletion(text: String, offset: Int, anchor: String, ok: Boolean = true) =
        DeletionUndo(text, offset, anchor) { t, o, a ->
            calls += Call(t, o, a)
            ok
        }

    @Test
    fun `annuler reinsere le texte efface avec sa position et son ancre`() {
        val undo = deletion("lo", 3, "hel")
        assertTrue(undo.run())
        assertEquals(listOf(Call("lo", 3, "hel")), calls)
    }

    @Test
    fun `l echec de la reinsertion est rapporte`() {
        assertFalse(deletion("x", 0, "a", ok = false).run())
    }

    @Test
    fun `des retours arriere successifs se regroupent en une seule action`() {
        // Champ « bonjour », curseur à la fin : trois appuis effacent « our », « o » puis « j ».
        val undo = deletion("r", 6, "bonjou")
        assertTrue(undo.tryMerge(deleted = "u", before = "bonjou"))
        assertTrue(undo.tryMerge(deleted = "o", before = "bonjo"))
        assertEquals("our", undo.text)
        assertEquals(4, undo.offset)
        assertEquals("bonj", undo.anchor)
    }

    @Test
    fun `le texte restitue est dans l ordre d origine`() {
        val undo = deletion("c", 2, "ab")
        undo.tryMerge("b", "ab")
        undo.tryMerge("a", "a")
        undo.run()
        assertEquals(Call("abc", 0, ""), calls.single())
    }

    @Test
    fun `un curseur deplace ne se regroupe pas`() {
        val undo = deletion("r", 6, "bonjou")
        assertFalse(undo.tryMerge(deleted = "m", before = "salutm"))
        assertEquals("r", undo.text)
        assertEquals(6, undo.offset)
    }

    @Test
    fun `sans position connue le regroupement reste possible`() {
        val undo = deletion("b", -1, "a")
        assertTrue(undo.tryMerge("a", "a"))
        assertEquals(-1, undo.offset)
        assertEquals("ab", undo.text)
    }

    @Test
    fun `une ancre vide ne se regroupe jamais`() {
        assertFalse(deletion("a", 0, "").tryMerge("x", "x"))
    }

    @Test
    fun `l historique expose la derniere action sans la retirer`() {
        val history = UndoHistory()
        assertEquals(null, history.last())
        val undo = deletion("a", 0, "")
        history.push(undo)
        assertTrue(history.last() === undo)
        assertEquals(1, history.size)
    }
}
