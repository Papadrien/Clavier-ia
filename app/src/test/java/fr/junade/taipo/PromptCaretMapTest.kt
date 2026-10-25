package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PromptCaretMapTest {

    // Texte « bonjour a tous », curseur après « bonjour » (7), trait du curseur d'un caractère : affiché « bonjour|a tous ».
    private fun offset(displayOffset: Int, ellipsized: Boolean = false, before: Int = 7, cursor: Int = 7, length: Int = 14) =
        PromptCaretMap.textOffset(displayOffset, before, ellipsized, caretLength = 1, cursor = cursor, textLength = length)

    @Test
    fun `toucher avant le curseur`() {
        assertEquals(3, offset(3))
        assertEquals(0, offset(0))
        assertEquals(7, offset(7))
    }

    @Test
    fun `toucher sur le trait du curseur ne bouge pas le curseur`() {
        assertEquals(7, offset(8))
    }

    @Test
    fun `toucher apres le curseur saute le trait du curseur`() {
        assertEquals(8, offset(9))
        assertEquals(14, offset(15))
    }

    @Test
    fun `une position hors du texte est ramenee dans le texte`() {
        assertEquals(14, offset(60))
    }

    @Test
    fun `avec un debut tronque, le deplacement se fait depuis la fin visible`() {
        // Texte de 20 caractères, curseur à 18, affiché « …xyz| » : avant = 4 (le « … » et 3 caractères visibles).
        assertEquals(15, offset(0, ellipsized = true, before = 4, cursor = 18, length = 20))
        assertEquals(15, offset(1, ellipsized = true, before = 4, cursor = 18, length = 20))
        assertEquals(16, offset(2, ellipsized = true, before = 4, cursor = 18, length = 20))
        assertEquals(18, offset(4, ellipsized = true, before = 4, cursor = 18, length = 20))
    }

    @Test
    fun `sans troncature, un toucher au tout debut place le curseur au debut du texte`() {
        assertEquals(0, offset(0, ellipsized = false, before = 7, cursor = 7))
    }
}
