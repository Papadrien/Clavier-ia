package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OneHandedWidthTest {

    private val density = 2f

    @Test
    fun `mode desactive la zone est identique a celle sans mode`() {
        listOf(720f, 1346f, 2000f).forEach { px ->
            assertEquals(
                KeyboardWidth.forAvailableWidth(px, density),
                KeyboardWidth.forAvailableWidth(px, density, OneHandedMode.OFF),
            )
        }
    }

    @Test
    fun `a gauche la zone est collee au bord gauche et reduite a 80 pour cent`() {
        val w = KeyboardWidth.forAvailableWidth(720f, density, OneHandedMode.LEFT) // 360 dp
        assertEquals(0f, w.leftPx)
        assertEquals(576f, w.widthPx)
        assertEquals(576f, w.rightPx)
    }

    @Test
    fun `a droite la zone est collee au bord droit`() {
        val w = KeyboardWidth.forAvailableWidth(720f, density, OneHandedMode.RIGHT)
        assertEquals(144f, w.leftPx)
        assertEquals(576f, w.widthPx)
        assertEquals(720f, w.rightPx)
    }

    @Test
    fun `le pourcentage est pris en compte et borne`() {
        assertEquals(504f, KeyboardWidth.forAvailableWidth(720f, density, OneHandedMode.LEFT, 70).widthPx)
        assertEquals(648f, KeyboardWidth.forAvailableWidth(720f, density, OneHandedMode.LEFT, 90).widthPx)
        // Hors bornes : ramene a 70 % / 90 %.
        assertEquals(504f, KeyboardWidth.forAvailableWidth(720f, density, OneHandedMode.LEFT, 10).widthPx)
        assertEquals(648f, KeyboardWidth.forAvailableWidth(720f, density, OneHandedMode.LEFT, 100).widthPx)
    }

    @Test
    fun `en paysage la reduction part de la largeur plafonnee et colle quand meme au bord`() {
        // 1600 px = 800 dp : classe large, zone normale plafonnee a 720 dp (1440 px).
        val left = KeyboardWidth.forAvailableWidth(1600f, density, OneHandedMode.LEFT)
        assertEquals(0f, left.leftPx)
        assertEquals(1152f, left.widthPx) // 80 % de 1440 px
        val right = KeyboardWidth.forAvailableWidth(1600f, density, OneHandedMode.RIGHT)
        assertEquals(1600f, right.rightPx)
        assertEquals(1152f, right.widthPx)
    }

    @Test
    fun `les touches ne depassent jamais la vue quel que soit le mode`() {
        OneHandedMode.entries.forEach { mode ->
            listOf(300f, 599f, 600f, 720f, 841f, 1280f, 2000f).forEach { dp ->
                (OneHandedWidth.MIN_PERCENT..OneHandedWidth.MAX_PERCENT step 5).forEach { percent ->
                    val w = KeyboardWidth.forAvailableWidth(dp * density, density, mode, percent)
                    assertTrue(w.leftPx >= 0f)
                    assertTrue(w.rightPx <= dp * density + 0.001f)
                }
            }
        }
    }

    @Test
    fun `une largeur nulle ou une densite invalide ne plantent pas`() {
        assertEquals(0f, KeyboardWidth.forAvailableWidth(0f, density, OneHandedMode.LEFT).widthPx)
        assertEquals(0f, KeyboardWidth.forAvailableWidth(-10f, density, OneHandedMode.RIGHT).widthPx)
        val w = KeyboardWidth.forAvailableWidth(500f, 0f, OneHandedMode.RIGHT)
        assertTrue(w.leftPx >= 0f)
        assertTrue(w.rightPx <= 500.001f)
    }

    @Test
    fun `le mode se relit depuis sa cle de stockage et retombe sur desactive`() {
        OneHandedMode.entries.forEach { assertEquals(it, OneHandedMode.fromStorageKey(it.storageKey)) }
        assertEquals(OneHandedMode.OFF, OneHandedMode.fromStorageKey(null))
        assertEquals(OneHandedMode.OFF, OneHandedMode.fromStorageKey("inconnu"))
    }

    @Test
    fun `seuls gauche et droite sont actifs`() {
        assertEquals(listOf(false, true, true), OneHandedMode.entries.map { it.isActive })
    }
}
