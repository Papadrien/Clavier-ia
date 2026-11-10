package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FloatingTransitionTest {

    private fun run(transition: FloatingTransition, onFrame: (FloatingTransition) -> Unit = {}): Int {
        var frames = 0
        while (transition.isRunning && frames < 600) {
            transition.step(1f / 60f)
            onFrame(transition)
            frames++
        }
        return frames
    }

    @Test
    fun `l'envol depasse un peu la carte flottante avant de se poser, en moins d'une demi-seconde`() {
        val transition = FloatingTransition { true }
        transition.setFloating(true, animate = true)
        assertTrue(transition.isMorphing)
        var peak = 0f
        val frames = run(transition) { peak = maxOf(peak, it.morphProgress) }
        assertTrue(peak > 1f, "effet ressort : léger dépassement")
        assertTrue(peak < 1.1f, "dépassement discret")
        assertTrue(frames < 30, "posé en moins d'une demi-seconde ($frames images)")
        assertEquals(1f, transition.morphProgress)
        assertFalse(transition.isMorphing)
    }

    @Test
    fun `l'atterrissage revient au clavier ancre`() {
        val transition = FloatingTransition { true }
        transition.setFloating(true, animate = false)
        transition.setFloating(false, animate = true)
        run(transition)
        assertEquals(0f, transition.morphProgress)
    }

    @Test
    fun `soulever la carte rebondit au-dessus de la taille visee puis s'y pose`() {
        val transition = FloatingTransition { true }
        transition.setLifted(true)
        var peak = 0f
        run(transition) { peak = maxOf(peak, it.liftScale) }
        assertTrue(peak > 1f + FloatingTransition.LIFT_SCALE, "petit rebond")
        assertEquals(1f + FloatingTransition.LIFT_SCALE, transition.liftScale, 0.0001f)
    }

    @Test
    fun `l'apparition sur place grossit avec un rebond et devient opaque`() {
        val transition = FloatingTransition { true }
        transition.playAppear()
        assertEquals(FloatingTransition.APPEAR_FROM_SCALE, transition.appearScale, 0.0001f)
        assertEquals(0f, transition.appearAlpha)
        var peak = 0f
        run(transition) { peak = maxOf(peak, it.appearScale) }
        assertTrue(peak > 1f, "petit rebond")
        assertEquals(1f, transition.appearScale)
        assertEquals(1f, transition.appearAlpha)
    }

    @Test
    fun `animations coupees, tout est pose directement`() {
        val transition = FloatingTransition { false }
        transition.setFloating(true, animate = true)
        transition.setLifted(true)
        transition.playAppear()
        assertFalse(transition.isRunning)
        assertEquals(1f, transition.morphProgress)
        assertEquals(1f, transition.appearAlpha)
    }
}
