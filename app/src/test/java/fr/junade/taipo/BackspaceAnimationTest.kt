package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Animation de l'icône retour arrière : déclenchements (appui, répétition, glissement, relâchement) et retour au repos. */
class BackspaceAnimationTest {

    private fun animation(enabled: Boolean = true) =
        BackspaceAnimation(density = 1f, animationsEnabled = { enabled }, random = Random(7))

    private fun BackspaceAnimation.alive() = (0 until capacity).count { isSparkAlive(it) }

    private fun BackspaceAnimation.settle() = repeat(150) { step(0.02f) }

    @Test
    fun `au repos rien ne tourne et l'icone est droite`() {
        val a = animation()
        assertFalse(a.isRunning)
        assertEquals(0f, a.iconAngle, 1e-6f)
        assertEquals(0, a.alive())
    }

    @Test
    fun `un appui lance le balancier vers la gauche, l'icone passe a droite puis se calme`() {
        val a = animation()
        a.onKey(100f, 50f, repeat = false, nowNanos = 1_000L)
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
    fun `un appui lance cinq etincelles qui s'eteignent`() {
        val a = animation()
        a.onKey(100f, 50f, repeat = false, nowNanos = 1_000L)
        assertEquals(5, a.alive())
        a.step(0.2f)
        (0 until a.capacity).filter { a.isSparkAlive(it) }.forEach {
            assertTrue(a.sparkAlpha(it) in 0f..1f)
            assertTrue(a.sparkRadius(it) in 0f..2f)
        }
        a.step(1f)
        assertEquals(0, a.alive())
    }

    @Test
    fun `l'appui maintenu espace ses gerbes d'etincelles`() {
        val a = animation()
        a.onKey(100f, 50f, repeat = false, nowNanos = 1_000_000_000L)
        a.onKey(100f, 50f, repeat = true, nowNanos = 1_060_000_000L)
        assertEquals(5, a.alive())
        a.onKey(100f, 50f, repeat = true, nowNanos = 1_300_000_000L)
        assertEquals(10, a.alive())
    }

    @Test
    fun `animations systeme coupees : rien ne se declenche`() {
        val a = animation(enabled = false)
        a.onKey(100f, 50f, repeat = false, nowNanos = 1_000L)
        a.onPress(300f)
        a.onDrag(200f, 50f, 100f, 2)
        a.onSwipeRelease(200f, 50f)
        assertFalse(a.isRunning)
        assertEquals(0, a.alive())
    }

    @Test
    fun `le glissement incline l'icone a gauche, laisse une trainee et une gerbe par mot`() {
        val a = animation()
        a.onPress(300f)
        a.onDrag(280f, 50f, 20f, 0)
        assertEquals(2, a.alive()) // traînée : deux étincelles tous les 12 dp
        a.step(0.15f)
        assertTrue(a.iconAngle < 0f)
        a.onDrag(250f, 50f, 50f, 1)
        assertEquals(2 + 2 + 4, a.alive()) // nouvelle traînée et gerbe du premier mot
        a.onDrag(248f, 50f, 52f, 1)
        assertEquals(8, a.alive()) // ni traînée (moins de 12 dp) ni gerbe (même nombre de mots)
    }

    @Test
    fun `au relachement la gerbe part et l'icone se redresse avec un rebond`() {
        val a = animation()
        a.onPress(300f)
        a.onDrag(200f, 50f, 100f, 2)
        a.settle()
        assertEquals(BackspaceIconPose.DRAG_TILT_DEGREES, a.iconAngle, 0.5f)
        a.onSwipeRelease(200f, 50f)
        assertEquals(8, a.alive())
        a.settle()
        assertFalse(a.isRunning)
        assertEquals(0f, a.iconAngle, 1e-3f)
    }

    @Test
    fun `un geste interrompu redresse l'icone sans etincelles`() {
        val a = animation()
        a.onPress(300f)
        a.onDrag(250f, 50f, 50f, 0)
        val before = a.alive()
        a.onSwipeCancel()
        assertEquals(before, a.alive())
        a.settle()
        assertFalse(a.isRunning)
    }

    @Test
    fun `cancel coupe tout`() {
        val a = animation()
        a.onKey(100f, 50f, repeat = false, nowNanos = 1_000L)
        a.cancel()
        assertFalse(a.isRunning)
        assertEquals(0f, a.iconAngle, 1e-6f)
        assertEquals(0, a.alive())
    }

    @Test
    fun `le tampon est circulaire : jamais plus d'etincelles que sa capacite`() {
        val a = animation()
        repeat(30) { a.onKey(0f, 0f, repeat = false, nowNanos = it * 1_000L) }
        assertEquals(a.capacity, a.alive())
    }
}
