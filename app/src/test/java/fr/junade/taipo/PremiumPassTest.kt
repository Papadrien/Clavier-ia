package fr.junade.taipo

import fr.junade.taipo.PremiumPass.DebugOverride
import fr.junade.taipo.ai.SystemPromptPreferences
import fr.junade.taipo.clipboard.ClipboardItems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumPassTest {

    @Test
    fun `sans forcage, l'etat est celui de Google Play`() {
        for (debug in listOf(true, false)) {
            assertTrue(PremiumPass.resolve(purchased = true, override = DebugOverride.NONE, debugBuild = debug))
            assertFalse(PremiumPass.resolve(purchased = false, override = DebugOverride.NONE, debugBuild = debug))
        }
    }

    @Test
    fun `le forcage du debug l'emporte dans un build debug`() {
        assertTrue(PremiumPass.resolve(purchased = false, override = DebugOverride.ACTIVE, debugBuild = true))
        assertFalse(PremiumPass.resolve(purchased = true, override = DebugOverride.INACTIVE, debugBuild = true))
    }

    @Test
    fun `le forcage est ignore dans un build de publication`() {
        assertFalse(PremiumPass.resolve(purchased = false, override = DebugOverride.ACTIVE, debugBuild = false))
        assertTrue(PremiumPass.resolve(purchased = true, override = DebugOverride.INACTIVE, debugBuild = false))
    }

    @Test
    fun `presse-papiers - 5 epingles et 12 h sans pass, 50 et 7 jours avec`() {
        assertEquals(5, ClipboardItems.maxPinned(premium = false))
        assertEquals(50, ClipboardItems.maxPinned(premium = true))
        assertEquals(12 * 60 * 60 * 1000L, ClipboardItems.historyRetentionMillis(premium = false))
        assertEquals(7 * 24 * 60 * 60 * 1000L, ClipboardItems.historyRetentionMillis(premium = true))
    }

    @Test
    fun `un prompt personnalise ne sert qu'avec le pass`() {
        assertEquals("perso", SystemPromptPreferences.activePrompt("perso", "défaut", premium = true))
        assertEquals("défaut", SystemPromptPreferences.activePrompt("perso", "défaut", premium = false))
        assertEquals("défaut", SystemPromptPreferences.activePrompt(null, "défaut", premium = true))
    }
}
