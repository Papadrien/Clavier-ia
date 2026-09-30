package fr.junade.taipo.clipboard

import fr.junade.taipo.clipboard.ClipboardItems.PinResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 2.5 : cartes du panneau (dernière copie puis épinglés) et règles d'épinglage. */
class ClipboardItemsTest {

    private fun pinned(id: Long, text: String) = PinnedClip(id, text, pinnedAtMillis = id)
    private fun last(text: String, sensitive: Boolean = false) = ClipboardSuggestionState.Suggestion(text, sensitive)

    @Test
    fun `sans copie ni epingle le panneau est vide`() {
        assertTrue(ClipboardItems.build(null, emptyList()).isEmpty())
    }

    @Test
    fun `la derniere copie vient en premier puis les epingles dans l ordre donne`() {
        val items = ClipboardItems.build(last("copie"), listOf(pinned(2, "b"), pinned(1, "a")))
        assertEquals(listOf("copie", "b", "a"), items.map { it.text })
        assertEquals(listOf(true, false, false), items.map { it.isLastClip })
        assertEquals(listOf(null, 2L, 1L), items.map { it.pinnedId })
    }

    @Test
    fun `une copie deja epinglee n apparait qu une fois, comme carte epinglee`() {
        val items = ClipboardItems.build(last("a"), listOf(pinned(1, "a"), pinned(2, "b")))
        assertEquals(listOf("a", "b"), items.map { it.text })
        assertTrue(items[0].pinned)
        assertTrue(items[0].isLastClip)
        assertFalse(items[1].isLastClip)
    }

    @Test
    fun `sans copie seuls les epingles sont montres`() {
        val items = ClipboardItems.build(null, listOf(pinned(1, "a")))
        assertEquals(1, items.size)
        assertTrue(items[0].pinned)
        assertFalse(items[0].isLastClip)
    }

    @Test
    fun `le caractere sensible de la derniere copie est conserve`() {
        val item = ClipboardItems.build(last("4970 1012", sensitive = true), emptyList()).single()
        assertTrue(item.sensitive)
        assertFalse(item.pinned)
    }

    @Test
    fun `un texte normal peut etre epingle`() {
        assertNull(ClipboardItems.pinRefusal("Bonjour", sensitive = false))
    }

    @Test
    fun `un contenu sensible ne peut pas etre epingle`() {
        assertEquals(PinResult.SENSITIVE, ClipboardItems.pinRefusal("Bonjour", sensitive = true))
    }

    @Test
    fun `un texte vide ou blanc ne peut pas etre epingle`() {
        assertEquals(PinResult.EMPTY, ClipboardItems.pinRefusal("", sensitive = false))
        assertEquals(PinResult.EMPTY, ClipboardItems.pinRefusal(" \n ", sensitive = false))
    }

    @Test
    fun `la taille maximale est acceptee et au dela refusee`() {
        assertNull(ClipboardItems.pinRefusal("a".repeat(ClipboardItems.MAX_PINNED_CHARS), sensitive = false))
        assertEquals(
            PinResult.TOO_LONG,
            ClipboardItems.pinRefusal("a".repeat(ClipboardItems.MAX_PINNED_CHARS + 1), sensitive = false),
        )
    }

    @Test
    fun `le sensible l emporte sur les autres refus`() {
        assertEquals(PinResult.SENSITIVE, ClipboardItems.pinRefusal("", sensitive = true))
    }
}
