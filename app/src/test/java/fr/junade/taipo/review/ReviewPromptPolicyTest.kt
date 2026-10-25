package fr.junade.taipo.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewPromptPolicyTest {

    @Test
    fun `aucune demande avant le troisieme lancement`() {
        for (launch in 0..2) assertFalse(ReviewPromptPolicy.shouldPrompt(launch), "lancement $launch")
    }

    @Test
    fun `la premiere demande est au troisieme lancement`() {
        assertTrue(ReviewPromptPolicy.shouldPrompt(3))
    }

    @Test
    fun `puis une demande tous les vingt lancements, quatre au total`() {
        assertEquals(listOf(3, 23, 43, 63), (1..500).filter(ReviewPromptPolicy::shouldPrompt))
    }

    @Test
    fun `plus aucune demande apres le soixante-troisieme lancement`() {
        for (launch in 64..1000) assertFalse(ReviewPromptPolicy.shouldPrompt(launch), "lancement $launch")
    }

    @Test
    fun `le calendrier est termine des le soixante-troisieme lancement`() {
        assertFalse(ReviewPromptPolicy.isFinished(62))
        assertTrue(ReviewPromptPolicy.isFinished(63))
        assertTrue(ReviewPromptPolicy.isFinished(64))
    }

    @Test
    fun `aucune demande entre deux echeances`() {
        for (launch in 4..22) assertFalse(ReviewPromptPolicy.shouldPrompt(launch), "lancement $launch")
        for (launch in 24..42) assertFalse(ReviewPromptPolicy.shouldPrompt(launch), "lancement $launch")
    }
}
