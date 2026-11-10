package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigitSwipeTest {
    private val slow = DigitSwipe.MIN_DURATION_MS

    @Test
    fun `le seuil suit la hauteur de la touche mais jamais moins de deux fois la tolerance tactile`() {
        assertEquals(60f, DigitSwipe.threshold(100f, 8f), 0.001f)
        assertEquals(24f, DigitSwipe.threshold(20f, 12f), 0.001f)
    }

    @Test
    fun `un glissement assez long, vertical et pas trop bref saisit le chiffre`() {
        assertTrue(DigitSwipe.isTriggered(dx = 5f, dy = 60f, thresholdPx = 60f, elapsedMs = slow))
        assertTrue(DigitSwipe.isTriggered(dx = -30f, dy = 70f, thresholdPx = 60f, elapsedMs = 300L))
        assertTrue(DigitSwipe.isTriggered(dx = 40f, dy = 70f, thresholdPx = 60f, elapsedMs = slow), "un peu oblique (pouce)")
    }

    @Test
    fun `trop court, vers le haut, en diagonale ou trop rapide, rien`() {
        assertFalse(DigitSwipe.isTriggered(dx = 0f, dy = 59f, thresholdPx = 60f, elapsedMs = slow), "trop court")
        assertFalse(DigitSwipe.isTriggered(dx = 0f, dy = -80f, thresholdPx = 60f, elapsedMs = slow), "vers le haut")
        assertFalse(DigitSwipe.isTriggered(dx = 60f, dy = 70f, thresholdPx = 60f, elapsedMs = slow), "diagonale vers la touche suivante")
        assertFalse(DigitSwipe.isTriggered(dx = 0f, dy = 80f, thresholdPx = 60f, elapsedMs = slow - 1), "doigt qui glisse en frappe rapide")
    }

    @Test
    fun `l'avancement de l'animation va de 0 a 1`() {
        assertEquals(0.5f, DigitSwipe.progress(dx = 0f, dy = 30f, thresholdPx = 60f))
        assertEquals(1f, DigitSwipe.progress(dx = 0f, dy = 120f, thresholdPx = 60f))
        assertEquals(0f, DigitSwipe.progress(dx = 0f, dy = -5f, thresholdPx = 60f))
        assertEquals(0f, DigitSwipe.progress(dx = 50f, dy = 20f, thresholdPx = 60f))
    }

    @Test
    fun `la touche du haut reste visee tant que le doigt descend`() {
        assertTrue(DigitSwipe.keepsKey(dx = 10f, dy = 40f, touchSlopPx = 8f))
        assertFalse(DigitSwipe.keepsKey(dx = 0f, dy = 5f, touchSlopPx = 8f), "simple tremblement")
        assertFalse(DigitSwipe.keepsKey(dx = 60f, dy = 20f, touchSlopPx = 8f), "glissement de côté")
        assertFalse(DigitSwipe.keepsKey(dx = 0f, dy = -30f, touchSlopPx = 8f), "vers le haut")
    }
}
