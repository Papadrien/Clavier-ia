package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Panneau IA (20/11/2026) : actions épinglées dans la barre, deux au plus. */
class AiPinsTest {

    @Test
    fun `par defaut Corriger et Transcription, et rien d epingle se distingue de jamais regle`() {
        assertEquals(listOf(AiAction.CORRECT, AiAction.TRANSCRIPTION), AiPins.parse(null))
        assertEquals(emptyList<AiAction>(), AiPins.parse(""))
        assertEquals(listOf(AiAction.TONE, AiAction.CHAT), AiPins.parse("tone,chat"))
    }

    @Test
    fun `lecture tolerante aux cles inconnues, doublons et listes trop longues`() {
        assertEquals(listOf(AiAction.TONE), AiPins.parse("inconnue,tone,tone"))
        assertEquals(listOf(AiAction.CORRECT, AiAction.TONE), AiPins.parse("correct,tone,chat"))
        assertEquals(listOf(AiAction.TRANSLATE, AiAction.CHAT), AiPins.parse(AiPins.serialize(listOf(AiAction.TRANSLATE, AiAction.CHAT))))
    }

    @Test
    fun `appui long epingle, retire, ou refuse une troisieme action`() {
        val one = AiPins.toggle(emptyList(), AiAction.TONE)
        assertEquals(AiPins.Toggle.Pinned(listOf(AiAction.TONE)), one)
        val two = AiPins.toggle(one.pins, AiAction.TRANSLATE)
        assertEquals(listOf(AiAction.TONE, AiAction.TRANSLATE), two.pins)
        val full = AiPins.toggle(two.pins, AiAction.CHAT)
        assertTrue(full is AiPins.Toggle.Full)
        assertEquals(two.pins, full.pins)
        val removed = AiPins.toggle(two.pins, AiAction.TONE)
        assertEquals(AiPins.Toggle.Unpinned(listOf(AiAction.TRANSLATE)), removed)
    }

    @Test
    fun `ordre de la rangee la premiere epinglee contre le bouton IA`() {
        val order = AiPins.rowOrder(listOf(AiAction.CORRECT, AiAction.TRANSCRIPTION))
        // Non épinglées d'abord (cachées), puis la seconde épinglée, puis la première, juste avant le bouton IA.
        assertEquals(listOf(AiAction.TONE, AiAction.TRANSLATE, AiAction.CHAT, AiAction.TRANSCRIPTION, AiAction.CORRECT), order)
        assertEquals(AiAction.entries.size, order.toSet().size)
    }

    @Test
    fun `astuce affichee sans epingle ou aux premieres ouvertures`() {
        assertTrue(AiPins.showsPinTip(emptyList(), panelOpenCount = 50))
        assertTrue(AiPins.showsPinTip(AiPins.DEFAULT, panelOpenCount = 1))
        assertFalse(AiPins.showsPinTip(AiPins.DEFAULT, panelOpenCount = AiPins.TIP_FIRST_OPENINGS))
    }
}
