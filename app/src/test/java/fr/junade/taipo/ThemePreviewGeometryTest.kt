package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lot T5 : la disposition de l'aperçu de thème tient dans la vue et ne superpose pas ses touches. */
class ThemePreviewGeometryTest {

    private val width = 300f
    private val height = 400f
    private val keys = ThemePreviewGeometry.keys(width, height)

    @Test
    fun `une vue vide ne dessine rien`() {
        assertTrue(ThemePreviewGeometry.keys(0f, 100f).isEmpty())
        assertTrue(ThemePreviewGeometry.keys(100f, 0f).isEmpty())
    }

    @Test
    fun `la barre a quatre boutons et le clavier quinze touches`() {
        assertEquals(4, keys.count { it.kind == PreviewKeyKind.BAR_BUTTON })
        assertEquals(15, keys.count { it.kind != PreviewKeyKind.BAR_BUTTON })
        assertEquals(1, keys.count { it.kind == PreviewKeyKind.SPACE })
        assertEquals(1, keys.count { it.kind == PreviewKeyKind.ENTER })
    }

    @Test
    fun `toutes les touches tiennent dans la vue`() {
        for (key in keys) {
            assertTrue(key.left >= 0f && key.top >= 0f && key.right <= width && key.bottom <= height, "hors de la vue : $key")
            assertTrue(key.width > 0f && key.height > 0f, "touche vide : $key")
        }
    }

    @Test
    fun `deux touches ne se superposent jamais`() {
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val a = keys[i]
            val b = keys[j]
            val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
            assertTrue(!overlap, "superposition : $a et $b")
        }
    }

    @Test
    fun `la barre d espace fait deux colonnes et l entree une`() {
        val space = keys.first { it.kind == PreviewKeyKind.SPACE }
        val enter = keys.first { it.kind == PreviewKeyKind.ENTER }
        val letter = keys.first { it.kind == PreviewKeyKind.LETTER }
        assertTrue(space.width > 2f * letter.width)
        assertEquals(letter.width, enter.width, 0.01f)
        assertEquals(space.top, enter.top, 0.01f)
    }

    @Test
    fun `le clavier est centre horizontalement`() {
        val left = keys.minOf { it.left }
        val right = keys.maxOf { it.right }
        assertEquals(left, width - right, 0.01f)
    }
}
