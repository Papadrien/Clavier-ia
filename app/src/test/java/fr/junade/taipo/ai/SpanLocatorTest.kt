package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpanLocatorTest {

    @Test
    fun `la zone reste a sa place d origine`() {
        assertEquals(6, SpanLocator.find("Salut Bonjour tout le monde", "Bonjour", 6))
    }

    @Test
    fun `du texte ajoute avant decale la zone`() {
        assertEquals(10, SpanLocator.find("0123456789Bonjour", "Bonjour", 6))
    }

    @Test
    fun `du texte ajoute apres ne change rien`() {
        assertEquals(0, SpanLocator.find("Bonjour tout le monde, et la suite", "Bonjour tout le monde,", 0))
    }

    @Test
    fun `zone modifiee ou absente`() {
        assertEquals(-1, SpanLocator.find("Salut tout le monde", "Bonjour", 0))
        assertEquals(-1, SpanLocator.find("", "Bonjour", 0))
        assertEquals(-1, SpanLocator.find("Bonjour", "", 0))
    }

    @Test
    fun `plusieurs occurrences, la plus proche de la position attendue`() {
        val text = "ok ok ok"
        assertEquals(3, SpanLocator.find(text, "ok", 4))
        assertEquals(0, SpanLocator.find(text, "ok", 1))
        assertEquals(6, SpanLocator.find(text, "ok", 100))
    }
}
