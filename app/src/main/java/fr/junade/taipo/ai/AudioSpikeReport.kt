package fr.junade.taipo.ai

import java.util.Locale

/** Mesures d'une transcription du spike 14.1 (voir [AudioSpikeReport.format]). */
data class AudioSpikeMeasures(
    val modelLabel: String,
    val audioSeconds: Double,
    val wavBytes: Int,
    /** `Content.AudioBytes` ou `Content.AudioFile` : celle des deux formes d'API qui a fonctionné. */
    val apiUsed: String,
    /** Pourquoi la première forme d'API a échoué (le spike essaie ensuite l'autre), ou null. */
    val firstApiFailure: String?,
    /** Durée de chargement du moteur, ou null s'il était déjà chargé avec l'audio. */
    val loadMs: Long?,
    val firstChunkMs: Long?,
    val inferenceMs: Long,
    val ramBeforeMb: Long,
    val ramPeakMb: Long,
    val text: String,
)

/**
 * Compte rendu lisible du spike 14.1 (pop-up) : les mesures demandées par la story (durée, latence, RAM) puis le texte
 * obtenu, à comparer à ce qui a été dit. Logique pure, testée en JVM. Le texte n'est jamais écrit dans les journaux
 * (story 14.6) : il n'existe que dans ce compte rendu.
 */
object AudioSpikeReport {

    fun format(m: AudioSpikeMeasures): String {
        val loc = Locale.FRANCE
        val realtime = if (m.audioSeconds > 0) m.inferenceMs / 1000.0 / m.audioSeconds else 0.0
        val words = m.text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return buildString {
            appendLine("Spike 14.1 — ${m.modelLabel}")
            appendLine(String.format(loc, "Audio : %.1f s, WAV PCM 16 kHz mono, %d Ko", m.audioSeconds, m.wavBytes / 1024))
            appendLine("API : ${m.apiUsed}")
            m.firstApiFailure?.let { appendLine("Première forme d'API refusée : $it") }
            appendLine(
                if (m.loadMs == null) "Chargement du moteur : déjà chargé"
                else String.format(loc, "Chargement du moteur : %.1f s", m.loadMs / 1000.0),
            )
            m.firstChunkMs?.let { appendLine(String.format(loc, "Premier morceau de texte : %.1f s", it / 1000.0)) }
            appendLine(String.format(loc, "Inférence : %.1f s (%.2f × la durée de l'audio)", m.inferenceMs / 1000.0, realtime))
            appendLine("RAM (PSS du processus) : ${m.ramBeforeMb} Mo avant, pic ${m.ramPeakMb} Mo")
            appendLine("Texte : ${m.text.trim().length} caractères, $words mots")
            append("---\n")
            append(m.text.trim().ifEmpty { "(aucun texte renvoyé)" })
        }
    }
}
