package fr.papadrien.clavier.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    private var recordingJob: Job? = null
    private var stream: OnlineStream? = null

    /** Appelé (sur un thread d'arrière-plan) si 20s de silence continu sont détectées. */
    var onSilenceTimeout: (() -> Unit)? = null

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
        record.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize)
            var silenceStartAt = -1L
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val samples = FloatArray(read) { i -> buffer[i] / 32768.0f }
                voiceStream.acceptWaveform(samples, SAMPLE_RATE)
                engine.decodeAvailable(voiceStream)

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
        }
    }

    /** Arrête l'enregistrement et renvoie le texte transcrit final. */
    suspend fun stopAndGetResult(): String {
        recordingJob?.cancelAndJoin()
        recordingJob = null
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
        return engine.currentText(finalStream)
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
