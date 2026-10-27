package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyboardBottomMarginTest {

    @Test
    fun `la valeur par defaut reprend la marge portrait d'origine`() {
        assertEquals(KeyboardMetrics.PORTRAIT.bottomMarginDp, KeyboardBottomMargin.DEFAULT_DP.toFloat())
    }

    @Test
    fun `une valeur hors bornes est ramenee dans la plage`() {
        assertEquals(KeyboardBottomMargin.MIN_DP, KeyboardBottomMargin.clamp(-10))
        assertEquals(KeyboardBottomMargin.MAX_DP, KeyboardBottomMargin.clamp(500))
        assertEquals(42, KeyboardBottomMargin.clamp(42))
    }
}
