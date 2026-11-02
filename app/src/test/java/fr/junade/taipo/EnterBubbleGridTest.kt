package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnterBubbleGridTest {

    @Test
    fun `sans multilingue la bulle ne contient que le mode une main`() {
        assertEquals(listOf(listOf(EnterBubbleGrid.ONE_HANDED)), EnterBubbleGrid.grid(multilingual = false))
    }

    @Test
    fun `avec le multilingue la bulle contient le globe puis le mode une main`() {
        assertEquals(listOf(listOf(EnterBubbleGrid.GLOBE, EnterBubbleGrid.ONE_HANDED)), EnterBubbleGrid.grid(multilingual = true))
    }

    @Test
    fun `les deux symboles sont distincts et dans la zone d'usage prive`() {
        assertNotEquals(EnterBubbleGrid.GLOBE, EnterBubbleGrid.ONE_HANDED)
        listOf(EnterBubbleGrid.GLOBE, EnterBubbleGrid.ONE_HANDED).forEach { assertTrue(it in '\uE000'..'\uF8FF') }
    }
}
