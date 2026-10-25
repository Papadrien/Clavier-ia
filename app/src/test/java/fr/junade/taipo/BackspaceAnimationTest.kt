package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Animation de l'icône retour arrière : déclenchements (appui, répétition, glissement, relâchement) et retour au repos. */
class BackspaceAnimationTest {

    private fun animation(enabled: Boolean = true) =
        BackspaceAnimation(density = 1f, animationsEnabled = { enabled })

    private fun BackspaceAnimation.settle() = repeat(150) { step(0.02f) }

    @Test
    fun `au repos rien ne tourne et l'icone est droite`() {
        val a = animation()
        assertFalse(a.isRunning)
        assertEquals(0f, a.iconAngle, 1e-6f)
    }

    @Test
    fun `un appui lance le balancier vers la gauche, l'icone passe a droite puis se calme`() {
        val a = animation()
        a.onKey(repeat = false)
        assertTrue(a.isRunning)
        a.step(0f)
        assertEquals(BackspaceIconPose.SWING_TAP_DEGREES, a.iconAngle, 1e-3f)
        var sawRight = false
        repeat(60) {
            a.step(0.015f)
            if (a.iconAngle > 5f) sawRight = true
        }
        assertTrue(sawRight)
        a.settle()
        assertFalse(a.isRunning)
        assertEquals(0f, a.iconAngle, 1e-3f)
    }

    @Test
    fun `animations systeme coupees - rien ne se declenche`() {
        val a = animation(enabled = false)
        a.onKey(repeat = false)
        a.onPress()
        a.onDrag(100f, 2)
        a.onSwipeRelease()
        assertFalse(a.isRunning)
    }

    @Test
    fun `le glissement incline l'icone a gauche et la fait osciller par mot`() {
        val a = animation()
        a.onPress()
        a.onDrag(20f, 0)
        a.step(0.15f)
        assertTrue(a.iconAngle < 0f)
        a.onDrag(50f, 1)
        a.onDrag(52f, 1)
        assertTrue(a.isRunning)
    }

    @Test
    fun `au relachement l'icone se redresse avec un rebond`() {
        val a = animation()
        a.onPress()
        a.onDrag(100f, 2)
        a.settle()
        assertEquals(BackspaceIconPose.DRAG_TILT_DEGREES, a.iconAngle, 0.5f)
        a.onSwipeRelease()
        a.settle()
        assertFalse(a.isRunning)
        assertEquals(0f, a.iconAngle, 1e-3f)
    }

    @Test
    fun `un geste interrompu redresse l'icone sans etincelles`() {
        val a = animation()
        a.onPress()
        a.onDrag(50f, 0)
        a.onSwipeCancel()
        a.settle()
        assertFalse(a.isRunning)
    }

    @Test
    fun `cancel coupe tout`() {
        val a = animation()
        a.onKey(repeat = false)
        a.cancel()
        assertFalse(a.isRunning)
        assertEquals(0f, a.iconAngle, 1e-6f)
    }
}
