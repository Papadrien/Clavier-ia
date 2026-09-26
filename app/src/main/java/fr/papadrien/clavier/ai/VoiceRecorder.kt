package fr.papadrien.clavier.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Capture audio (AudioRecord, 16 kHz mono PCM16) et alimente en continu un
 * flux [OnlineStream] sherpa-onnx pendant l'enregistrement.
 *
 * Décision 6.3 : en mode appui bref, arrêt automatique après 20s de silence
 * continu. Simplification assumée : le silence est détecté par un simple
 * seuil d'amplitude RMS (pas de VAD dédiée dans ce prototype) — à affiner si
 * le comportement observé n'est pas satisfaisant en usage réel.
 *
 * L'appelant est responsable de vérifier la permission RECORD_AUDIO avant
 * d'appeler [start] (voir décision permission micro refusée du 23/09/2026,
 * câblée dans ClavierIme).
 */
class VoiceRecorder(private val engine: VoiceEngine, private val scope: CoroutineScope) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var decodeJob: Job? = null
    private var audioChannel: Channel<FloatArray>? = null
    private var stream: OnlineStream? = null

    /** Appelé (sur un thread d'arrière-plan) si 20s de silence continu sont détectées. */
    var onSilenceTimeout: (() -> Unit)? = null

    private val _partialText = MutableStateFlow("")

    /**
     * Hypothèse de transcription courante, mise à jour en continu pendant
     * l'enregistrement au fil du décodage streaming sherpa-onnx (et non plus
     * seulement disponible à la fin via [stopAndGetResult]). Permet à
     * l'appelant d'insérer le texte au fur et à mesure dans le champ de
     * saisie. Chaque nouvelle valeur remplace entièrement la précédente : le
     * décodeur en streaming peut réviser des mots déjà "affichés" au fil des
     * mots suivants, donc l'appelant doit toujours retirer l'insertion
     * précédente avant d'insérer la nouvelle plutôt que de simplement
     * concaténer.
     */
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    @Suppress("MissingPermission") // Vérifié par l'appelant avant d'appeler start().
    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBufferSize > 0) minBufferSize else SAMPLE_RATE

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        audioRecord = record
        val voiceStream = engine.createStream()
        stream = voiceStream
        _partialText.value = ""
        record.startRecording()

        // File illimitée entre capture et décodage : si decodeAvailable() met du
        // temps (typiquement son tout premier appel, avec le coût d'initialisation
        // du graphe ONNX), la capture continue à vider le micro sans attendre, au
        // lieu de laisser le petit buffer natif d'AudioRecord déborder et perdre
        // le tout début de la phrase (bug observé sur les enregistrements un peu
        // longs, retour du 26/09/2026).
        val channel = Channel<FloatArray>(capacity = Channel.UNLIMITED)
        audioChannel = channel

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize)
            var silenceStartAt = -1L
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val samples = FloatArray(read) { i -> buffer[i] / 32768.0f }
                channel.trySend(samples)

                val now = System.currentTimeMillis()
                if (rms(buffer, read) < SILENCE_RMS_THRESHOLD) {
                    if (silenceStartAt < 0) {
                        silenceStartAt = now
                    } else if (now - silenceStartAt >= SILENCE_TIMEOUT_MS) {
                        onSilenceTimeout?.invoke()
                        break
                    }
                } else {
                    silenceStartAt = -1L
                }
            }
            channel.close()
        }

        decodeJob = scope.launch(Dispatchers.Default) {
            for (samples in channel) {
                voiceStream.acceptWaveform(samples, SAMPLE_RATE)
                engine.decodeAvailable(voiceStream)
                _partialText.value = engine.currentText(voiceStream)
            }
        }
    }

    /** Arrête l'enregistrement et renvoie le texte transcrit final. */
    suspend fun stopAndGetResult(): String {
        captureJob?.cancelAndJoin()
        captureJob = null
        // La capture ferme déjà le channel en fin de boucle normale, mais pas si
        // elle est annulée en plein `record.read()` bloquant : on le referme donc
        // ici aussi (idempotent) pour que le décodage ait bien tout reçu avant de
        // s'arrêter à son tour.
        audioChannel?.close()
        decodeJob?.join()
        decodeJob = null
        audioChannel = null
        audioRecord?.let {
            it.stop()
            it.release()
        }
        audioRecord = null

        val finalStream = stream
        stream = null
        if (finalStream == null) return ""

        finalStream.inputFinished()
        engine.decodeAvailable(finalStream)
        val finalText = engine.currentText(finalStream)
        _partialText.value = finalText
        return finalText
    }

    private fun rms(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / length)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SILENCE_RMS_THRESHOLD = 500.0
        private const val SILENCE_TIMEOUT_MS = 20_000L
    }
}
