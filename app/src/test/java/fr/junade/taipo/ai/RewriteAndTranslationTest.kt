package fr.junade.taipo.ai

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.model.AiModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Changement de ton et traduction (20/11/2026) : nettoyage des réponses, prompts, langues proposées selon le modèle. */
class RewriteAndTranslationTest {

    @Test
    fun `le garde-fou retire l introduction et les guillemets autour de tout le texte`() {
        assertEquals("Bonjour Paul, à demain.", RewriteSafeguard.clean("Voici le texte réécrit :\nBonjour Paul, à demain."))
        assertEquals("Hello there", RewriteSafeguard.clean("  \"Hello there\"  "))
        assertEquals("Salut", RewriteSafeguard.clean("« Salut »"))
        // Une citation au milieu reste.
        assertEquals("Il a dit « oui » hier", RewriteSafeguard.clean("Il a dit « oui » hier"))
        assertNull(RewriteSafeguard.clean("   "))
        // Une première ligne longue n'est pas une introduction.
        val long = "Ceci est une vraie première ligne de message, assez longue pour ne pas être une introduction du modèle :\nSuite"
        assertEquals(long, RewriteSafeguard.clean(long))
    }

    @Test
    fun `le tour utilisateur porte le ton ou la langue cible et le texte`() {
        val tone = TonePrompt.userTurn(Tone.CONCISE, "Mon texte")
        assertTrue(tone.startsWith("Tone: concise"))
        assertTrue(tone.endsWith("Text:\nMon texte"))
        val translation = TranslationPrompt.userTurn(TranslationLanguage("es", "Spanish"), "Bonjour")
        assertTrue(translation.startsWith("Target language: Spanish."))
        assertTrue(translation.endsWith("Text:\nBonjour"))
    }

    @Test
    fun `langues proposees selon le modele`() {
        assertTrue(TranslationLanguages.forModel(AiModel.ULTRA_LEGER).isEmpty())
        assertEquals(KeyboardLanguage.entries.size, TranslationLanguages.forModel(AiModel.LEGER).size)
        val extended = TranslationLanguages.forModel(AiModel.PERFORMANT)
        assertTrue(extended.size > KeyboardLanguage.entries.size)
        assertEquals(extended.size, extended.map { it.tag }.toSet().size, "pas de doublon")
        assertEquals(extended, TranslationLanguages.forModel(AiModel.CUSTOM))
        assertFalse(ToneSupport.supports(AiModel.ULTRA_LEGER))
        assertTrue(ToneSupport.supports(AiModel.LEGER))
    }

    @Test
    fun `langue cible par defaut et langues recentes`() {
        val available = TranslationLanguages.forModel(AiModel.EQUILIBRE)
        assertEquals("en", TranslationLanguages.defaultTarget(available, null, KeyboardLanguage.FR)?.tag)
        assertEquals("fr", TranslationLanguages.defaultTarget(available, null, KeyboardLanguage.EN)?.tag)
        assertEquals("ja", TranslationLanguages.defaultTarget(available, "ja", KeyboardLanguage.FR)?.tag)
        // Langue retenue mais plus proposée (modèle plus petit) : repli sur l'anglais.
        assertEquals("en", TranslationLanguages.defaultTarget(TranslationLanguages.forModel(AiModel.LEGER), "ja", KeyboardLanguage.FR)?.tag)
        assertNull(TranslationLanguages.defaultTarget(emptyList(), "ja", KeyboardLanguage.FR))
        val recent = TranslationLanguages.pushRecent(listOf("de", "es"), "es")
        assertEquals(listOf("es", "de"), recent)
        assertEquals("es", TranslationLanguages.ordered(available, recent).first().tag)
        assertEquals(available.size, TranslationLanguages.ordered(available, recent).size)
    }
}
