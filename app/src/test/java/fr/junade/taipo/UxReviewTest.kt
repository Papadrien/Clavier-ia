package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Revue UX : étapes 3 et 4 de l'accueil, durée du bandeau, déclencheurs des astuces de gestes. */
class UxReviewTest {

    @Test
    fun `etapes 3 et 4 verrouillees tant que Taipo n'est pas choisi`() {
        val state = OnboardingState(enabled = true, selected = false, tried = true, aiReady = true)
        assertEquals(StepStatus.LOCKED, state.tryIt)
        assertEquals(StepStatus.LOCKED, state.activateAi)
        assertFalse(state.complete)
    }

    @Test
    fun `etape 3 faite apres l'essai, etape 4 faite avec un modele installe`() {
        val start = OnboardingState(enabled = true, selected = true)
        assertEquals(StepStatus.CURRENT, start.tryIt)
        assertEquals(StepStatus.CURRENT, start.activateAi)
        val done = start.copy(tried = true, aiReady = true)
        assertEquals(StepStatus.DONE, done.tryIt)
        assertEquals(StepStatus.DONE, done.activateAi)
        assertTrue(done.complete)
    }

    @Test
    fun `l'essai demande trois mots`() {
        assertFalse(OnboardingState.triedWith("Bonjour à"))
        assertFalse(OnboardingState.triedWith("   "))
        assertTrue(OnboardingState.triedWith("Bonjour à tous"))
        assertTrue(OnboardingState.triedWith(" un\ndeux  trois quatre"))
    }

    @Test
    fun `duree du bandeau selon la longueur du message`() {
        assertEquals(2_500L, MessagePopup.bannerDuration("Court"))
        assertEquals(2_500L + 60 * 40L, MessagePopup.bannerDuration("x".repeat(100)))
        assertEquals(6_000L, MessagePopup.bannerDuration("x".repeat(1_000)))
    }

    @Test
    fun `astuce d'effacement au cinquieme appui consecutif sur retour arriere`() {
        val counter = GestureTips.Counter()
        repeat(GestureTips.BACKSPACES_FOR_TIP - 1) { assertFalse(counter.onKey(backspace = true)) }
        assertFalse(counter.onKey(backspace = false)) // une autre touche remet le compte à zéro
        repeat(GestureTips.BACKSPACES_FOR_TIP - 1) { assertFalse(counter.onKey(backspace = true)) }
        assertTrue(counter.onKey(backspace = true))
    }

    @Test
    fun `astuce du curseur au troisieme deplacement a la main`() {
        val counter = GestureTips.Counter()
        assertFalse(counter.onManualCursorMove())
        assertFalse(counter.onManualCursorMove())
        assertTrue(counter.onManualCursorMove())
        assertFalse(counter.onManualCursorMove())
    }
}
