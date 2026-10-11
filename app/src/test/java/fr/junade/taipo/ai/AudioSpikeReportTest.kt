package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spike 14.1 : le compte rendu doit contenir toutes les mesures demandées par la story. */
class AudioSpikeReportTest {

    private fun measures(
        loadMs: Long? = 8_200,
        firstApiFailure: String? = null,
        text: String = "Bonjour tout le monde",
    ) = AudioSpikeMeasures(
        modelLabel = "Gemma 4 E2B (instruct) (Équilibré)",
        audioSeconds = 12.34,
        wavBytes = 395_000,
        apiUsed = "Content.AudioBytes",
        firstApiFailure = firstApiFailure,
        loadMs = loadMs,
        firstChunkMs = 1_900,
        inferenceMs = 3_400,
        ramBeforeMb = 480,
        ramPeakMb = 3_120,
        text = text,
    )

    @Test
    fun `le compte rendu donne duree, API, latences, memoire et texte`() {
        val report = AudioSpikeReport.format(measures())
        assertTrue(report.contains("12,3 s"), report)
        assertTrue(report.contains("Content.AudioBytes"), report)
        assertTrue(report.contains("Chargement du moteur : 8,2 s"), report)
        assertTrue(report.contains("Premier morceau de texte : 1,9 s"), report)
        assertTrue(report.contains("Inférence : 3,4 s (0,28 × la durée de l'audio)"), report)
        assertTrue(report.contains("480 Mo avant, pic 3120 Mo"), report)
        assertTrue(report.contains("21 caractères, 4 mots"), report)
        assertTrue(report.endsWith("Bonjour tout le monde"), report)
    }

    @Test
    fun `moteur deja charge et echec de la premiere forme d'API sont signales`() {
        val report = AudioSpikeReport.format(measures(loadMs = null, firstApiFailure = "IllegalArgumentException: format"))
        assertTrue(report.contains("Chargement du moteur : déjà chargé"), report)
        assertTrue(report.contains("Première forme d'API refusée : IllegalArgumentException: format"), report)
    }

    @Test
    fun `aucun texte renvoye est dit clairement`() {
        val report = AudioSpikeReport.format(measures(text = "  "))
        assertTrue(report.contains("0 caractères, 0 mots"), report)
        assertTrue(report.endsWith("(aucun texte renvoyé)"), report)
        assertFalse(report.contains("Première forme"), report)
    }
}
