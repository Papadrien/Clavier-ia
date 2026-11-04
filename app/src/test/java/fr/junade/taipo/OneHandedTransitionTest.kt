package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OneHandedTransitionTest {

    // Écran de 1000 px : zone des touches à 80 % collée à gauche ou à droite, ou pleine largeur.
    private val off = KeyboardWidth(0f, 1000f)
    private val leftZone = KeyboardWidth(0f, 800f)
    private val rightZone = KeyboardWidth(200f, 800f)

    private fun run(transition: OneHandedTransition, seconds: Float, onFrame: (OneHandedTransition) -> Unit = {}) {
        var now = 1_000_000_000L
        transition.advance(now)
        repeat((seconds * 120).toInt()) {
            now += 8_333_333L
            transition.advance(now)
            onFrame(transition)
        }
    }

    @Test
    fun `l'activation amene la zone sur sa cible puis s'arrete`() {
        val t = OneHandedTransition()
        t.moveTo(OneHandedMode.OFF, OneHandedMode.LEFT, off, leftZone, 0f, 900f)
        assertTrue(t.isMoving)
        run(t, 1.5f)
        assertFalse(t.isRunning)
        assertEquals(0f, t.zone.leftPx, 0.001f)
        assertEquals(800f, t.zone.widthPx, 0.001f)
        assertEquals(1f, t.controlsAppear, 0.001f)
        assertEquals(900f, t.controlsCx, 0.001f)
        assertEquals(1f, t.flipX, 0.001f)
    }

    @Test
    fun `la zone depasse un peu sa cible a la fermeture du clavier sans depasser 12 pour cent de la course`() {
        val t = OneHandedTransition()
        t.moveTo(OneHandedMode.OFF, OneHandedMode.LEFT, off, leftZone, 0f, 900f)
        var minWidth = Float.MAX_VALUE
        run(t, 1.5f) { minWidth = minOf(minWidth, it.zone.widthPx) }
        // Course de 200 px : le ressort passe sous 800 px, mais d'au plus 12 % de la course (24 px).
        assertTrue(minWidth < 800f)
        assertTrue(minWidth > 800f - 24f)
    }

    @Test
    fun `la zone est posee en moins de 0,5 s`() {
        val t = OneHandedTransition()
        t.moveTo(OneHandedMode.OFF, OneHandedMode.LEFT, off, leftZone, 0f, 900f)
        run(t, 0.5f)
        assertFalse(t.isMoving)
    }

    @Test
    fun `les boutons apparaissent apres le clavier`() {
        val t = OneHandedTransition()
        t.moveTo(OneHandedMode.OFF, OneHandedMode.RIGHT, off, rightZone, 0f, 100f)
        run(t, 0.05f)
        assertEquals(0f, t.controlsAppear, 0.001f)
        run(t, 0.6f)
        assertEquals(1f, t.controlsAppear, 0.001f)
    }

    @Test
    fun `le changement de cote deplace la zone et les boutons et retourne la fleche`() {
        val t = OneHandedTransition()
        t.moveTo(OneHandedMode.LEFT, OneHandedMode.RIGHT, leftZone, rightZone, 900f, 100f)
        assertEquals(1f, t.flipX, 0.001f)
        run(t, 1.5f)
        assertEquals(200f, t.zone.leftPx, 0.001f)
        assertEquals(100f, t.controlsCx, 0.001f)
        assertEquals(-1f, t.flipX, 0.001f)
        assertEquals(1f, t.controlsAppear, 0.001f)
    }

    @Test
    fun `la sortie fait rentrer les boutons avant que le clavier s'elargisse`() {
        val t = OneHandedTransition()
        t.moveTo(OneHandedMode.RIGHT, OneHandedMode.OFF, rightZone, off, 100f, 0f)
        run(t, 0.05f)
        assertEquals(200f, t.zone.leftPx, 0.001f)
        run(t, 1.5f)
        assertEquals(0f, t.zone.leftPx, 0.001f)
        assertEquals(1000f, t.zone.widthPx, 0.001f)
        assertEquals(0f, t.controlsAppear, 0.001f)
    }

    @Test
    fun `l'appui ecrase le bouton puis il revient a sa taille`() {
        val t = OneHandedTransition()
        t.setPressed(OneHandedControls.Button.EXIT, pressed = true, animate = true)
        run(t, 0.5f)
        assertEquals(OneHandedSpring.PRESSED_SCALE, t.pressScale(OneHandedControls.Button.EXIT), 0.001f)
        t.setPressed(OneHandedControls.Button.EXIT, pressed = false, animate = true)
        run(t, 1f)
        assertEquals(1f, t.pressScale(OneHandedControls.Button.EXIT), 0.001f)
        assertFalse(t.isRunning)
    }

    @Test
    fun `sans animation le changement est direct`() {
        val t = OneHandedTransition()
        t.setPressed(OneHandedControls.Button.SWITCH_SIDE, pressed = true, animate = false)
        assertEquals(OneHandedSpring.PRESSED_SCALE, t.pressScale(OneHandedControls.Button.SWITCH_SIDE), 0.0001f)
        assertFalse(t.isRunning)
    }

    @Test
    fun `stop pose tout sur les cibles`() {
        val t = OneHandedTransition()
        t.moveTo(OneHandedMode.OFF, OneHandedMode.LEFT, off, leftZone, 0f, 900f)
        t.stop()
        assertFalse(t.isRunning)
        assertEquals(800f, t.zone.widthPx, 0.001f)
        assertEquals(1f, t.controlsAppear, 0.001f)
    }
}
