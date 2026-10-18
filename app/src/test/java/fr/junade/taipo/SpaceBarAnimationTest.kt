package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaceBarAnimationTest {

    // Densité 1 : les pixels valent des dp. Demi-trait de 22.
    private fun animation(enabled: Boolean = true) = SpaceBarAnimation(
        density = 1f,
        animationsEnabled = { enabled },
        halfLengthPx = { 22f },
    )

    private fun SpaceBarAnimation.run(seconds: Float) {
        repeat((seconds * 120f).toInt()) { step(1f / 120f) }
    }

    @Test
    fun `au repos rien ne bouge et l animation ne tourne pas`() {
        val a = animation()
        assertFalse(a.isRunning)
        a.step(0.016f)
        assertEquals(0f, a.rightOffset)
        assertEquals(0f, a.leftOffset)
    }

    @Test
    fun `glisser vers la droite fait mener le bout droit et traîner le bout gauche`() {
        val a = animation()
        a.onDrag(60f, 50f)
        a.run(0.08f)
        assertTrue(a.headIsRight)
        assertTrue(a.rightOffset > a.leftOffset, "le bout avant est devant")
        assertTrue(a.rightOffset - a.leftOffset > 0f, "le trait s'étire")
        assertTrue(a.isRunning)
    }

    @Test
    fun `glisser vers la gauche inverse les roles`() {
        val a = animation()
        a.onDrag(-60f, 50f)
        a.run(0.08f)
        assertFalse(a.headIsRight)
        assertTrue(a.leftOffset < a.rightOffset)
    }

    @Test
    fun `le trait se stabilise pres de la cible et reste dans la course`() {
        val a = animation()
        a.onDrag(500f, 50f)
        a.run(2f)
        assertEquals(50f, a.rightOffset, 6f)
        assertEquals(50f, a.leftOffset, 6f)
        assertTrue(a.rightOffset < 60f)
    }

    @Test
    fun `au lacher le trait revient au centre puis l animation s arrete`() {
        val a = animation()
        a.onDrag(80f, 50f)
        a.run(0.5f)
        a.onRelease()
        a.run(3f)
        assertFalse(a.isRunning)
        assertEquals(0f, a.rightOffset, 0.1f)
        assertEquals(0f, a.leftOffset, 0.1f)
    }

    @Test
    fun `le retour au centre rebondit avant de se stabiliser`() {
        val a = animation()
        a.onDrag(80f, 50f)
        a.run(1f)
        a.onRelease()
        var crossedBelowZero = false
        repeat(360) {
            a.step(1f / 120f)
            if (a.rightOffset < -0.2f) crossedBelowZero = true
        }
        assertTrue(crossedBelowZero, "le bout avant dépasse le centre")
    }

    @Test
    fun `les deux bouts ne se croisent jamais`() {
        val a = animation()
        a.onDrag(300f, 50f)
        a.run(0.3f)
        a.onDrag(-300f, 50f)
        repeat(240) {
            a.step(1f / 120f)
            val length = 44f + a.rightOffset - a.leftOffset
            assertTrue(length >= 12f - 0.01f, "longueur $length")
        }
    }

    @Test
    fun `animations coupees le trait reste immobile`() {
        val a = animation(enabled = false)
        a.onDrag(80f, 50f)
        a.run(0.5f)
        assertEquals(0f, a.rightOffset)
        assertEquals(0f, a.leftOffset)
        assertFalse(a.isRunning)
    }

    @Test
    fun `cancel remet tout au repos`() {
        val a = animation()
        a.onDrag(80f, 50f)
        a.run(0.2f)
        a.cancel()
        assertFalse(a.isRunning)
        assertEquals(0f, a.rightOffset)
        assertEquals(0f, a.leftOffset)
    }

    @Test
    fun `la course est proportionnelle puis butee en douceur`() {
        assertEquals(0f, SpaceBarPose.limitedTravel(0f, 50f))
        assertEquals(9f, SpaceBarPose.limitedTravel(10f, 50f), 0.3f)
        assertTrue(SpaceBarPose.limitedTravel(1000f, 50f) <= 50f)
        assertEquals(-SpaceBarPose.limitedTravel(30f, 50f), SpaceBarPose.limitedTravel(-30f, 50f), 0.0001f)
        assertEquals(0f, SpaceBarPose.limitedTravel(30f, 0f))
    }

    @Test
    fun `etire le bout avant gonfle et le bout arriere s amincit`() {
        val rest = 1.5f
        assertEquals(rest, SpaceBarPose.headRadius(0f, rest), 0.0001f)
        assertEquals(rest, SpaceBarPose.tailRadius(0f, rest), 0.0001f)
        assertTrue(SpaceBarPose.headRadius(0.5f, rest) > rest)
        assertTrue(SpaceBarPose.tailRadius(0.5f, rest) < rest)
        assertTrue(SpaceBarPose.tailRadius(5f, rest) >= rest * SpaceBarPose.MIN_TAIL_RATIO - 0.0001f)
    }

    @Test
    fun `raccourci les deux bouts s epaississent`() {
        val rest = 1.5f
        assertTrue(SpaceBarPose.headRadius(-0.3f, rest) > rest)
        assertEquals(SpaceBarPose.headRadius(-0.3f, rest), SpaceBarPose.tailRadius(-0.3f, rest), 0.0001f)
    }

    @Test
    fun `l allongement compare la longueur courante a la longueur de repos`() {
        assertEquals(0f, SpaceBarPose.stretch(44f, 44f))
        assertEquals(0.5f, SpaceBarPose.stretch(66f, 44f), 0.0001f)
        assertEquals(-0.25f, SpaceBarPose.stretch(33f, 44f), 0.0001f)
    }

    @Test
    fun `la teinte est bornee`() {
        assertEquals(0f, SpaceBarPose.tint(0f))
        assertEquals(SpaceBarPose.MAX_TINT, SpaceBarPose.tint(9f), 0.0001f)
        assertEquals(SpaceBarPose.tint(0.4f), SpaceBarPose.tint(-0.4f), 0.0001f)
    }

    @Test
    fun `le melange de couleurs interpole chaque canal`() {
        val from = 0xFF000000.toInt()
        val to = 0xFFFFFFFF.toInt()
        assertEquals(from, SpaceBarPose.blend(from, to, 0f))
        assertEquals(to, SpaceBarPose.blend(from, to, 1f))
        assertEquals(0xFF808080.toInt(), SpaceBarPose.blend(from, to, 0.5f))
    }
}
