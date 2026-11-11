package fr.junade.taipo.analytics

import fr.junade.taipo.testing.testContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Audit PRIV-01 et TEST-01 : la mesure d'usage reste désactivée tant que l'utilisateur n'a pas choisi. */
@RunWith(RobolectricTestRunner::class)
class AnalyticsConsentTest {

    @Test
    fun `sans reponse, la mesure est desactivee`() {
        val preferences = AnalyticsPreferences(testContext)
        assertFalse(preferences.consentAnswered)
        assertFalse(preferences.isEnabled)
    }

    @Test
    fun `un refus compte comme une reponse`() {
        val preferences = AnalyticsPreferences(testContext)
        preferences.isEnabled = false
        assertTrue(preferences.consentAnswered)
        assertFalse(preferences.isEnabled)
    }

    @Test
    fun `un accord active la mesure`() {
        val preferences = AnalyticsPreferences(testContext)
        preferences.isEnabled = true
        assertTrue(preferences.consentAnswered)
        assertTrue(AnalyticsPreferences(testContext).isEnabled)
    }
}
