package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OneHandedControlsTest {

    // Zone libre de 144 px de large (20 % de 720 px) et 400 px de haut ; rayon max 44, marge 12, rayon min 24.
    private fun layout(freeLeft: Float = 576f, freeRight: Float = 720f, height: Float = 400f) =
        OneHandedControls.layout(freeLeft, freeRight, height, maxRadius = 44f, margin = 12f, minRadius = 24f)

    @Test
    fun `les boutons sont centres dans la zone libre et autour du milieu`() {
        val l = checkNotNull(layout())
        assertEquals(648f, l.cx)
        assertEquals(44f, l.radius) // min(44, 72 - 12) = 44
        assertEquals(200f, (l.switchCy + l.exitCy) / 2f)
        assertTrue(l.switchCy < l.exitCy)
    }

    @Test
    fun `le rayon est limite par la largeur de la zone et sa marge`() {
        // Zone de 80 px : 40 - 12 = 28 < 44.
        assertEquals(28f, checkNotNull(layout(freeLeft = 640f)).radius)
    }

    @Test
    fun `une zone trop etroite ou trop basse n'affiche aucun bouton`() {
        assertNull(layout(freeLeft = 700f)) // 20 px : rayon 10 - 12 < 24
        assertNull(layout(freeLeft = 720f)) // largeur nulle
        assertNull(layout(height = 100f)) // les deux boutons ne tiennent pas
        assertNull(layout(height = 0f))
    }

    @Test
    fun `les boutons restent dans la hauteur`() {
        val l = checkNotNull(layout())
        assertTrue(l.switchCy - l.radius >= 0f)
        assertTrue(l.exitCy + l.radius <= 400f)
    }

    @Test
    fun `un appui sur un bouton le designe`() {
        val l = checkNotNull(layout())
        assertEquals(OneHandedControls.Button.SWITCH_SIDE, OneHandedControls.hit(l, l.cx, l.switchCy))
        assertEquals(OneHandedControls.Button.EXIT, OneHandedControls.hit(l, l.cx, l.exitCy))
    }

    @Test
    fun `la zone de toucher depasse un peu le dessin`() {
        val l = checkNotNull(layout())
        val justOutside = l.radius * 1.2f // hors du dessin, dans la zone de toucher (x1,4)
        assertEquals(OneHandedControls.Button.EXIT, OneHandedControls.hit(l, l.cx + justOutside, l.exitCy))
        assertNull(OneHandedControls.hit(l, l.cx + l.radius * 1.5f, l.exitCy))
    }

    @Test
    fun `entre les deux boutons le plus proche gagne`() {
        val l = checkNotNull(layout())
        val nearSwitch = (l.switchCy + l.exitCy) / 2f - 1f
        val nearExit = (l.switchCy + l.exitCy) / 2f + 1f
        assertEquals(OneHandedControls.Button.SWITCH_SIDE, OneHandedControls.hit(l, l.cx, nearSwitch))
        assertEquals(OneHandedControls.Button.EXIT, OneHandedControls.hit(l, l.cx, nearExit))
    }

    @Test
    fun `un appui loin des boutons ne designe rien`() {
        val l = checkNotNull(layout())
        assertNull(OneHandedControls.hit(l, 10f, 10f))
        assertNotNull(OneHandedControls.hit(l, l.cx, l.switchCy))
    }
}
