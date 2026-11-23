package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Animation « compteur » au changement de page (21/11/2026). */
class LayoutRollTest {

    @Test
    fun `sens selon l ordre des pages, rien pour les autres changements`() {
        assertEquals(1, LayoutRoll.direction(LayoutId.LETTERS, LayoutId.SYMBOLS_2))
        assertEquals(1, LayoutRoll.direction(LayoutId.SYMBOLS_2, LayoutId.SYMBOLS))
        assertEquals(-1, LayoutRoll.direction(LayoutId.SYMBOLS, LayoutId.LETTERS))
        assertEquals(-1, LayoutRoll.direction(LayoutId.SYMBOLS_2, LayoutId.LETTERS))
        assertNull(LayoutRoll.direction(LayoutId.LETTERS, LayoutId.LETTERS))
    }

    @Test
    fun `chaque touche reprend le caractere de la plus proche et part avec un delai selon la distance`() {
        val a = Key("a", "a", KeyAction.TypeChar('a'))
        val z = Key("z", "z", KeyAction.TypeChar('z'))
        val one = Key("1", "1", KeyAction.TypeChar('1'))
        val two = Key("2", "2", KeyAction.TypeChar('2'))
        val toggle = Key("toggle", "ABC", KeyAction.ToggleLayout)
        val old = listOf(LayoutRoll.Slot(a, 10f, 10f, "a"), LayoutRoll.Slot(z, 30f, 10f, "z"))
        val new = listOf(
            LayoutRoll.Slot(one, 10f, 10f, "1"),
            LayoutRoll.Slot(two, 30f, 10f, "2"),
            LayoutRoll.Slot(toggle, 10f, 50f, "ABC"),
        )
        val roll = LayoutRoll.start(1_000L, 1, old, new, originX = 10f, originY = 50f, unitPx = 20f, rowHeightPx = 40f, style = MotionStyle.SPRING)
        assertEquals("a", roll.outgoingLabel(one))
        assertEquals("z", roll.outgoingLabel(two))
        // La touche touchée démarre tout de suite ; la plus éloignée, un peu après.
        assertTrue(roll.progress(toggle, 1_050L) > 0f)
        assertTrue(roll.progress(two, 1_000L) == 0f)
        assertTrue(roll.isRunning(1_100L))
        assertFalse(roll.isRunning(10_000L))
        assertEquals(1f, roll.progress(two, 10_000L))
    }
}
