package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Retours haptiques des actions IA : durées, amplitudes et respect du réglage « désactivé ». */
class AiHapticCueTest {

    private val strong = HapticIntensity.STRONG.amplitude
    private val light = HapticIntensity.LIGHT.amplitude

    @Test
    fun `premier appui sur une action IA 7 ms fort`() {
        assertEquals(listOf(HapticPulse(0, 7, strong)), AiHapticCue.ACTION_START.pulses(HapticIntensity.MEDIUM))
    }

    @Test
    fun `fin de chargement vocal 6 ms faible`() {
        assertEquals(listOf(HapticPulse(0, 6, light)), AiHapticCue.LOADING_DONE.pulses(HapticIntensity.MEDIUM))
    }

    @Test
    fun `fin de saisie vocale 6 ms fort`() {
        assertEquals(listOf(HapticPulse(0, 6, strong)), AiHapticCue.RECORDING_STOP.pulses(HapticIntensity.MEDIUM))
    }

    @Test
    fun `fin d enregistrement de transcription deux impulsions fortes de 6 ms`() {
        val pulses = AiHapticCue.TRANSCRIPTION_STOP.pulses(HapticIntensity.MEDIUM)
        assertEquals(2, pulses.size)
        assertTrue(pulses.all { it.durationMs == 6L && it.amplitude == strong })
        assertEquals(0L, pulses[0].delayMs)
        assertTrue(pulses[1].delayMs > 0)
    }

    @Test
    fun `fin de traitement suit le niveau des parametres comme une frappe`() {
        for (level in listOf(HapticIntensity.LIGHT, HapticIntensity.MEDIUM, HapticIntensity.STRONG)) {
            assertEquals(listOf(HapticPulse(0, HapticIntensity.CLICK_DURATION_MS, level.amplitude)), AiHapticCue.PROCESSING_DONE.pulses(level))
        }
    }

    @Test
    fun `les fortes et faibles ne dependent pas du niveau choisi`() {
        for (cue in listOf(AiHapticCue.ACTION_START, AiHapticCue.LOADING_DONE, AiHapticCue.RECORDING_STOP, AiHapticCue.TRANSCRIPTION_STOP)) {
            assertEquals(cue.pulses(HapticIntensity.LIGHT), cue.pulses(HapticIntensity.STRONG))
        }
    }

    @Test
    fun `mode chat un mot genere 6 ms faible et fin de generation 6 ms moyen`() {
        assertEquals(listOf(HapticPulse(0, 6, light)), AiHapticCue.CHAT_WORD.pulses(HapticIntensity.STRONG))
        assertEquals(listOf(HapticPulse(0, 6, HapticIntensity.MEDIUM.amplitude)), AiHapticCue.CHAT_DONE.pulses(HapticIntensity.LIGHT))
    }

    @Test
    fun `vibrations desactivees aucune vibration IA`() {
        AiHapticCue.entries.forEach { assertTrue(it.pulses(HapticIntensity.OFF).isEmpty()) }
    }
}
