package fr.junade.taipo

import fr.junade.taipo.DeleteSwipeController.Companion.wordToDeleteBeforeCursor
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
}
