package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigitSwipeAnimationTest {

    private val key = Key("a", "a", KeyAction.TypeChar('a'), longPressChar = '1')
    private val other = Key("z", "z", KeyAction.TypeChar('z'), longPressChar = '2')

    private fun animation(enabled: Boolean = true) = DigitSwipeAnimation(animationsEnabled = { enabled })

    private fun DigitSwipeAnimation.run(seconds: Float) {
        repeat((seconds * 120f).toInt()) { step(1f / 120f) }
    }

    private fun committed(): DigitSwipeAnimation = animation().also {
        it.onPress(key)
        it.onDrag(1f)
        it.onCommit()
        it.run(1f)
    }

    @Test
    fun `au repos rien ne bouge et l animation ne tourne pas`() {
        val a = animation()
        assertFalse(a.isRunning)
        assertFalse(a.isAnimating(key))
        assertEquals(0f, a.letterProgress)
        assertEquals(1f, a.hintScale)
    }

    @Test
    fun `un simple appui n anime rien et se termine`() {
        val a = animation()
        a.onPress(key)
        assertFalse(a.isAnimating(key))
        a.onRelease()
        a.run(0.2f)
        assertFalse(a.isRunning)
        assertNull(a.key)
    }

    @Test
    fun `glisser a moitie etire la touche et efface l indice`() {
        val a = animation()
        a.onPress(key)
        a.onDrag(0.5f)
        a.run(0.4f)
        assertTrue(a.isTracking(key))
        assertTrue(a.isAnimating(key))
        assertEquals(0.5f, a.letterProgress, 0.05f)
        assertTrue(a.hintScale < 0.1f, "l indice a presque disparu")
    }

    @Test
    fun `la validation fait eclore le chiffre avec un pop`() {
        val a = animation()
        a.onPress(key)
        a.onDrag(1f)
        a.onCommit()
        a.run(0.05f)
        assertTrue(a.digitPop > 0f, "le chiffre grossit un instant")
        a.run(1f)
        assertEquals(1f, a.letterProgress, 0.02f)
        assertEquals(1f, a.digitBloom, 0.02f)
        assertEquals(0f, a.hintScale)
        assertFalse(a.isTracking(key))
        assertTrue(a.isAnimating(key))
    }

    @Test
    fun `relacher apres la saisie fait sortir le chiffre par le haut puis revenir l indice`() {
        val a = committed()
        a.onRelease()
        var maxExit = 0f
        var hintTooEarly = false
        repeat(360) {
            a.step(1f / 120f)
            if (a.key != null) {
                if (a.digitExit > maxExit) maxExit = a.digitExit
                if (a.digitExit < DigitSwipeAnimation.HINT_RETURN_EXIT && a.hintScale > 0f) hintTooEarly = true
            }
        }
        assertTrue(maxExit >= DigitSwipeAnimation.HINT_RETURN_EXIT, "le chiffre sort de la touche")
        assertFalse(hintTooEarly, "l indice attend que le chiffre soit sorti")
        assertFalse(a.isRunning)
        assertNull(a.key)
        assertEquals(1f, a.hintScale)
    }

    @Test
    fun `le chiffre ne revient pas sur ses pas au relachement`() {
        val a = committed()
        a.onRelease()
        var minBloom = a.digitBloom
        repeat(360) {
            a.step(1f / 120f)
            if (a.key != null && a.digitBloom < minBloom) minBloom = a.digitBloom
        }
        assertTrue(minBloom > 0.9f, "le chiffre reste au centre jusqu a sa sortie par le haut")
    }

    @Test
    fun `la lettre revient avec un rebond au relachement`() {
        val a = committed()
        a.onRelease()
        var minProgress = a.letterProgress
        repeat(360) {
            a.step(1f / 120f)
            if (a.key != null && a.letterProgress < minProgress) minProgress = a.letterProgress
        }
        assertTrue(minProgress < -0.02f, "le ressort dépasse le repos avant de se stabiliser")
    }

    @Test
    fun `relacher avant le seuil remet tout en place sans sortie du chiffre`() {
        val a = animation()
        a.onPress(key)
        a.onDrag(0.6f)
        a.run(0.3f)
        a.onRelease()
        var maxExit = 0f
        repeat(240) {
            a.step(1f / 120f)
            if (a.digitExit > maxExit) maxExit = a.digitExit
        }
        assertEquals(0f, maxExit)
        assertFalse(a.isRunning)
        assertNull(a.key)
    }

    @Test
    fun `les animations systeme coupees ne font rien`() {
        val a = animation(enabled = false)
        a.onPress(key)
        a.onDrag(1f)
        a.onCommit()
        a.onRelease()
        a.run(0.5f)
        assertNull(a.key)
        assertFalse(a.isRunning)
        assertFalse(a.isAnimating(key))
    }

    @Test
    fun `annuler remet tout au repos tout de suite`() {
        val a = animation()
        a.onPress(key)
        a.onDrag(0.8f)
        a.run(0.2f)
        a.cancel()
        assertNull(a.key)
        assertEquals(0f, a.letterProgress)
        assertFalse(a.isRunning)
    }

    @Test
    fun `une autre touche n est jamais animee`() {
        val a = animation()
        a.onPress(key)
        a.onDrag(0.8f)
        a.run(0.3f)
        assertFalse(a.isAnimating(other))
        assertFalse(a.isTracking(other))
    }

    @Test
    fun `un nouvel appui repart de zero`() {
        val a = committed()
        a.onPress(other)
        assertEquals(0f, a.letterProgress)
        assertTrue(a.isTracking(other))
        assertFalse(a.isAnimating(key))
    }
}

class DigitSwipePoseTest {

    @Test
    fun `la touche s etire vers le bas et s amincit avec l avancement`() {
        assertEquals(1f, DigitSwipePose.faceScaleX(0f))
        assertEquals(1f, DigitSwipePose.faceScaleY(0f))
        assertTrue(DigitSwipePose.faceScaleY(1f) > 1f)
        assertTrue(DigitSwipePose.faceScaleX(1f) < 1f)
    }

    @Test
    fun `le rebond de retour ecrase la touche sans excès`() {
        assertTrue(DigitSwipePose.faceScaleY(-0.3f) < 1f)
        assertEquals(DigitSwipePose.faceScaleY(-0.6f), DigitSwipePose.faceScaleY(-5f))
        assertEquals(DigitSwipePose.faceScaleY(1.3f), DigitSwipePose.faceScaleY(5f))
    }

    @Test
    fun `la lettre s efface avant la moitie du glissement et reste bornee`() {
        assertEquals(1f, DigitSwipePose.letterAlpha(0f))
        assertEquals(0.25f, DigitSwipePose.letterAlpha(0.5f), 0.001f)
        assertEquals(0f, DigitSwipePose.letterAlpha(1f))
        assertEquals(1f, DigitSwipePose.letterAlpha(-0.3f))
        assertEquals(1f, DigitSwipePose.letterScale(0f))
        assertTrue(DigitSwipePose.letterScale(-0.3f) > 1f)
        assertEquals(0.05f, DigitSwipePose.letterScale(2f))
    }

    @Test
    fun `le chiffre apparait en eclosant puis s efface en sortant`() {
        assertEquals(0f, DigitSwipePose.digitAlpha(0f, 0f))
        assertEquals(1f, DigitSwipePose.digitAlpha(1f, 0f))
        assertEquals(0.5f, DigitSwipePose.digitAlpha(1f, 0.5f), 0.001f)
        assertEquals(0f, DigitSwipePose.digitAlpha(1f, 1f))
        assertEquals(0f, DigitSwipePose.digitAlpha(1f, 1.2f))
    }

    @Test
    fun `l indice est plein des la moitie de sa taille`() {
        assertEquals(0f, DigitSwipePose.hintAlpha(0f))
        assertEquals(1f, DigitSwipePose.hintAlpha(0.5f))
        assertEquals(1f, DigitSwipePose.hintAlpha(1.2f))
    }
}
