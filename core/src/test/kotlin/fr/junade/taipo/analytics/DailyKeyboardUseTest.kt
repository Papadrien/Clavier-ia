package fr.junade.taipo.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DailyKeyboardUseTest {

    private val hour = 3_600_000

    @Test
    fun `jamais envoye, on envoie`() {
        assertTrue(DailyKeyboardUse.shouldSend(DailyKeyboardUse.NO_DAY, 20_000))
    }

    @Test
    fun `deja envoye aujourd hui, rien`() {
        assertFalse(DailyKeyboardUse.shouldSend(20_000, 20_000))
    }

    @Test
    fun `nouveau jour, on envoie`() {
        assertTrue(DailyKeyboardUse.shouldSend(20_000, 20_001))
        assertTrue(DailyKeyboardUse.shouldSend(20_000, 20_010))
    }

    @Test
    fun `horloge reculee d un jour (fuseau), pas de second envoi`() {
        assertFalse(DailyKeyboardUse.shouldSend(20_001, 20_000))
    }

    @Test
    fun `horloge reculee de plusieurs jours, on ne reste pas bloque`() {
        assertTrue(DailyKeyboardUse.shouldSend(20_010, 20_000))
    }

    @Test
    fun `le jour suit le fuseau local`() {
        val midnightUtc = 20_000L * UsageBuckets.DAY_MILLIS
        // 23 h UTC la veille = minuit à Paris l'hiver (UTC+1) : déjà le jour suivant en local.
        assertEquals(19_999L, DailyKeyboardUse.localDay(midnightUtc - hour, 0))
        assertEquals(20_000L, DailyKeyboardUse.localDay(midnightUtc - hour, hour))
        // 1 h UTC = 20 h la veille à New York (UTC-5).
        assertEquals(19_999L, DailyKeyboardUse.localDay(midnightUtc + hour, -5 * hour))
    }

    @Test
    fun `le nom de l evenement est un mot-cle valide`() {
        assertTrue(AnalyticsProps.isKeyword(AnalyticsEvents.KEYBOARD_USED))
    }
}
