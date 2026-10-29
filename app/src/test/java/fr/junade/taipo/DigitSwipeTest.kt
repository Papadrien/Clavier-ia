package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigitSwipeTest {
    @Test
    fun `le seuil suit la hauteur de la touche mais jamais moins de deux fois la tolerance tactile`() {
        assertEquals(40f, DigitSwipe.threshold(100f, 8f))
        assertEquals(24f, DigitSwipe.threshold(20f, 12f))
    }

    @Test
    fun `un glissement vers le bas assez long et vertical valide le geste`() {
        assertTrue(DigitSwipe.isTriggered(dx = 5f, dy = 40f, thresholdPx = 40f))
        assertTrue(DigitSwipe.isTriggered(dx = -20f, dy = 60f, thresholdPx = 40f))
    }

    @Test
    fun `trop court, vers le haut ou plus horizontal ne valide pas`() {
        assertFalse(DigitSwipe.isTriggered(dx = 0f, dy = 39f, thresholdPx = 40f))
        assertFalse(DigitSwipe.isTriggered(dx = 0f, dy = -60f, thresholdPx = 40f))
        assertFalse(DigitSwipe.isTriggered(dx = 70f, dy = 50f, thresholdPx = 40f))
    }

    @Test
    fun `l avancement monte de zero a un puis reste plafonne`() {
        assertEquals(0.5f, DigitSwipe.progress(dx = 0f, dy = 20f, thresholdPx = 40f))
        assertEquals(1f, DigitSwipe.progress(dx = 0f, dy = 80f, thresholdPx = 40f))
    }

    @Test
    fun `l avancement reste nul vers le haut, plus horizontal que vertical ou sans seuil`() {
        assertEquals(0f, DigitSwipe.progress(dx = 0f, dy = -5f, thresholdPx = 40f))
        assertEquals(0f, DigitSwipe.progress(dx = 70f, dy = 50f, thresholdPx = 40f))
        assertEquals(0f, DigitSwipe.progress(dx = 0f, dy = 10f, thresholdPx = 0f))
    }
}
