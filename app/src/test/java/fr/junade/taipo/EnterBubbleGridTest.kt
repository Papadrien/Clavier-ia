package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnterBubbleGridTest {

    @Test
    fun `sans multilingue la bulle contient le mode une main puis le clavier flottant`() {
        assertEquals(listOf(listOf(EnterBubbleGrid.ONE_HANDED, EnterBubbleGrid.FLOATING)), EnterBubbleGrid.grid(multilingual = false))
    }

    @Test
    fun `avec le multilingue la bulle contient le globe, le mode une main puis le clavier flottant`() {
        assertEquals(
            listOf(listOf(EnterBubbleGrid.GLOBE, EnterBubbleGrid.ONE_HANDED, EnterBubbleGrid.FLOATING)),
            EnterBubbleGrid.grid(multilingual = true),
        )
    }

    @Test
    fun `les symboles sont distincts et dans la zone d'usage prive`() {
        assertNotEquals(EnterBubbleGrid.GLOBE, EnterBubbleGrid.ONE_HANDED)
        assertEquals(3, EnterBubbleGrid.ICONS.size)
        EnterBubbleGrid.ICONS.forEach { assertTrue(it in '\uE000'..'\uF8FF') }
    }
}
