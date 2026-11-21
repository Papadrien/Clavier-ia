package fr.junade.taipo.ai

import fr.junade.taipo.AiWarmup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Optimisations du 21/11/2026 : plafond de réponse, préchargement du modèle, traduction phrase par phrase. */
class SpeedTuningTest {

    @Test
    fun `le plafond de reponse laisse de la marge au texte attendu`() {
        assertEquals(64, InferenceTuning.maxOutputTokens(0))
        // 400 caractères ≈ 100 à 130 jetons : le plafond (264) laisse la réponse entière, mais coupe une boucle.
        assertEquals(264, InferenceTuning.maxOutputTokens(400))
        assertEquals(1, InferenceTuning.GREEDY.topK)
    }

    @Test
    fun `prechargement au panneau toujours, au texte seulement apres un usage recent`() {
        val now = 10 * AiWarmup.RECENT_USE_MS
        assertTrue(AiWarmup.shouldPreload(now, lastUseMs = 0, loaded = false, loading = false, requireRecentUse = false))
        assertFalse(AiWarmup.shouldPreload(now, lastUseMs = 0, loaded = false, loading = false, requireRecentUse = true))
        assertTrue(AiWarmup.shouldPreload(now, lastUseMs = now - 60_000, loaded = false, loading = false, requireRecentUse = true))
        assertFalse(AiWarmup.shouldPreload(now, lastUseMs = now - 2 * AiWarmup.RECENT_USE_MS, loaded = false, loading = false, requireRecentUse = true))
        // Déjà chargé ou en cours de chargement : rien à faire.
        assertFalse(AiWarmup.shouldPreload(now, lastUseMs = now, loaded = true, loading = false, requireRecentUse = false))
        assertFalse(AiWarmup.shouldPreload(now, lastUseMs = now, loaded = false, loading = true, requireRecentUse = false))
    }

    @Test
    fun `traduction phrase par phrase avec les separateurs d origine`() {
        val pieces = SentenceTranslation.split("  Bonjour Paul. Ça va ?\nÀ demain")
        assertEquals(listOf("Bonjour Paul.", "Ça va ?", "À demain"), pieces.map { it.sentence })
        assertEquals(listOf("", " ", "\n"), pieces.map { it.gapBefore })
        val translated = listOf("Hello Paul.", "How are you?", "See you tomorrow")
        assertEquals("Hello Paul. How are you?\nSee you tomorrow", SentenceTranslation.assemble(pieces, translated))
        assertTrue(SentenceTranslation.split("   ").isEmpty())
        // Une clé par langue cible : la même phrase traduite en deux langues n'est pas confondue.
        assertFalse(SentenceTranslation.cacheKey("en", "Salut") == SentenceTranslation.cacheKey("es", "Salut"))
    }
}
