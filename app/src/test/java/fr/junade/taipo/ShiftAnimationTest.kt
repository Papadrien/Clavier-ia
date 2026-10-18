package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Animation « Bond » de l'icône Maj : transitions entre les trois états, retour au repos, majuscule automatique. */
class ShiftAnimationTest {

    private fun animation(enabled: Boolean = true) =
        ShiftAnimation(density = 1f, animationsEnabled = { enabled }, random = Random(3))

    private fun ShiftAnimation.go(to: ShiftState, auto: Boolean = false) = onState(to, auto, 100f, 50f, 24f)

    private fun ShiftAnimation.settle() = repeat(200) { step(0.02f) }

    private fun ShiftAnimation.alive() = (0 until sparks.capacity).count { sparks.isAlive(it) }

    private fun ShiftAnimation.assertRest(state: ShiftState) {
        assertEquals(ShiftIconPose.restFill(state), fill, 1e-2f, "remplissage $state")
        assertEquals(ShiftIconPose.restScaleY(state), scaleY, 1e-2f, "hauteur $state")
        assertEquals(1f, scaleX, 1e-2f, "largeur $state")
        assertEquals(ShiftIconPose.restLift(state), arrowLift, 1e-2f, "hauteur de la flèche $state")
        assertEquals(ShiftIconPose.restBarOpacity(state), barOpacity, 1e-2f, "barre $state")
        if (state == ShiftState.LOCK) {
            assertEquals(1f, barScale, 1e-2f)
            assertEquals(0f, barOffset, 1e-2f)
        }
    }

    @Test
    fun `au repos rien ne tourne`() {
        val a = animation()
        assertFalse(a.isRunning)
        assertEquals(ShiftState.OFF, a.state)
        a.assertRest(ShiftState.OFF)
    }

    @Test
    fun `quel que soit le changement d'etat, l'icone finit a la pose de repos du nouvel etat`() {
        for (from in ShiftState.values()) {
            for (to in ShiftState.values()) {
                for (auto in listOf(false, true)) {
                    val a = animation()
                    a.snap(from)
                    a.go(to, auto)
                    a.settle()
                    assertFalse(a.isRunning, "$from -> $to (auto=$auto) doit se calmer")
                    assertEquals(to, a.state)
                    a.assertRest(to)
                }
            }
        }
    }

    @Test
    fun `minuscule vers majuscule : la fleche s'ecrase, bondit, s'etire et se remplit`() {
        val a = animation()
        a.go(ShiftState.ON)
        a.step(0f)
        assertEquals(0.68f, a.scaleY, 1e-3f)
        assertEquals(1.18f, a.scaleX, 1e-3f)
        var minLift = 0f
        var maxScaleY = 0f
        var fillRises = true
        var lastFill = 0f
        repeat(100) {
            a.step(0.01f)
            if (a.arrowLift < minLift) minLift = a.arrowLift
            if (a.scaleY > maxScaleY) maxScaleY = a.scaleY
            if (a.fill < lastFill - 0.3f) fillRises = false
            lastFill = a.fill
        }
        assertTrue(minLift < -1f, "la flèche monte pendant le bond")
        assertTrue(maxScaleY > 1.05f, "la flèche s'étire en retombant")
        assertTrue(fillRises)
    }

    @Test
    fun `l'activation au doigt ne lance ni anneau ni etincelles, l'automatique lance un anneau`() {
        val tap = animation()
        tap.go(ShiftState.ON, auto = false)
        assertFalse(tap.ringActive)
        assertEquals(0, tap.alive())
        val auto = animation()
        auto.go(ShiftState.ON, auto = true)
        assertTrue(auto.ringActive)
        repeat(60) { auto.step(0.01f) }
        assertTrue(auto.ringProgress in 0f..1f)
        auto.settle()
        assertFalse(auto.ringActive)
    }

    @Test
    fun `l'automatique fait un second bond que l'activation au doigt n'a pas`() {
        fun lateLift(auto: Boolean): Float {
            val a = animation()
            a.go(ShiftState.ON, auto)
            repeat(38) { a.step(0.01f) } // juste avant le second bond (0,38 s)
            var minLift = 0f
            repeat(30) {
                a.step(0.01f)
                if (a.arrowLift < minLift) minLift = a.arrowLift
            }
            return minLift
        }
        assertTrue(lateLift(auto = true) < lateLift(auto = false) - 0.5f)
    }

    @Test
    fun `le verrouillage fait tomber la barre avec quatre etincelles`() {
        val a = animation()
        a.snap(ShiftState.ON)
        a.go(ShiftState.LOCK)
        a.step(0f)
        assertEquals(4, a.alive())
        assertEquals(1f, a.barOpacity, 1e-3f)
        assertTrue(a.barOffset < -5f, "la barre part du haut")
        assertEquals(0.8f, a.scaleY, 1e-3f)
        a.settle()
        a.assertRest(ShiftState.LOCK)
        assertEquals(0, a.alive())
    }

    @Test
    fun `de minuscule a verrouille, la barre arrive juste apres la majuscule`() {
        val a = animation()
        a.go(ShiftState.LOCK)
        a.step(0.1f)
        assertEquals(0f, a.barOpacity, 1e-3f)
        a.step(0.2f)
        assertEquals(1f, a.barOpacity, 1e-3f)
        a.settle()
        a.assertRest(ShiftState.LOCK)
    }

    @Test
    fun `le retour en minuscule vide la fleche et fait partir la barre`() {
        val a = animation()
        a.snap(ShiftState.LOCK)
        a.go(ShiftState.OFF)
        a.step(0f)
        assertEquals(1.22f, a.scaleY, 1e-3f)
        assertEquals(0.86f, a.scaleX, 1e-3f)
        a.step(0.15f)
        assertTrue(a.fill < 1f)
        assertTrue(a.barOffset > 0f, "la barre tombe")
        a.settle()
        a.assertRest(ShiftState.OFF)
    }

    @Test
    fun `un changement pendant l'animation repart de la pose courante`() {
        val a = animation()
        a.go(ShiftState.ON)
        a.step(0.1f)
        a.go(ShiftState.OFF)
        a.settle()
        a.assertRest(ShiftState.OFF)
        assertFalse(a.isRunning)
    }

    @Test
    fun `animations systeme coupees : pose directe, rien ne tourne`() {
        val a = animation(enabled = false)
        a.go(ShiftState.LOCK, auto = true)
        assertFalse(a.isRunning)
        assertEquals(ShiftState.LOCK, a.state)
        a.assertRest(ShiftState.LOCK)
        assertEquals(0, a.alive())
        assertFalse(a.ringActive)
    }

    @Test
    fun `cancel revient au repos de l'etat courant`() {
        val a = animation()
        a.go(ShiftState.LOCK, auto = true)
        a.step(0.05f)
        a.cancel()
        assertFalse(a.isRunning)
        a.assertRest(ShiftState.LOCK)
    }
}
