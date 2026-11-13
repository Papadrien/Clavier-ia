package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pilule « Nouveautés » : montrée les trois premières ouvertures du clavier, jusqu'à ce que la page soit ouverte. */
class WhatsNewTest {

    @Test
    fun `montree les trois premieres fois`() {
        assertTrue(WhatsNew.shouldShow(shownCount = 0, opened = false))
        assertTrue(WhatsNew.shouldShow(shownCount = 2, opened = false))
        assertFalse(WhatsNew.shouldShow(shownCount = 3, opened = false))
    }

    @Test
    fun `plus montree une fois la page ouverte`() {
        assertFalse(WhatsNew.shouldShow(shownCount = 0, opened = true))
    }

    @Test
    fun `la v3 a des nouveautes, sans doublon`() {
        assertEquals("3.0", WhatsNew.RELEASE)
        assertTrue(WhatsNew.items.isNotEmpty())
        assertEquals(WhatsNew.items.size, WhatsNew.items.map { it.title }.toSet().size)
    }
}
