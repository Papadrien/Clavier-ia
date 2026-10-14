package fr.junade.taipo.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import fr.junade.taipo.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Enregistreur de la transcription IA (story 14.2) : capture le micro en PCM 16 bits, 16 kHz, mono (le format que la
 * documentation Google du modèle demande), tout en mémoire, jusqu'à [MAX_SECONDS] (limite décidée après le spike 14.1,
 * qui remplace le découpage de la story 14.3). Aucun fichier n'est écrit : l'audio n'existe que dans la mémoire du
 * processus, et [stop] comme [cancel] le jettent. Distinct de [VoiceRecorder], qui alimente le décodeur en continu et
 * n'est donc pas réutilisable tel quel.
 *
 * L'appelant vérifie la permission RECORD_AUDIO avant [start]. Toutes les méthodes se rappellent depuis le thread principal.
 */
class AiTranscriptionRecorder(private val scope: CoroutineScope) {

    private var record: AudioRecord? = null
    private var captureJob: Job? = null
    private var samples = ShortArray(0)

    @Volatile private var filled = 0

    /** Appelé (thread d'arrière-plan) quand la durée maximale est atteinte : la capture est alors terminée. */
    var onLimitReached: (() -> Unit)? = null

    /** Appelé (thread d'arrière-plan) quand le micro ne fournit plus de données : la capture est alors terminée. */
    var onMicLost: (() -> Unit)? = null

    val isRecording: Boolean get() = record != null

    @Suppress("MissingPermission") // Vérifié par l'appelant avant d'appeler start().
    fun start() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = if (minBufferSize > 0) minBufferSize else SAMPLE_RATE
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw MicUnavailableException("AudioRecord not initialized (state ${audioRecord.state})")
        }
        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            audioRecord.release()
            throw MicUnavailableException("Microphone start refused", e)
        }
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.release()
            throw MicUnavailableException("The microphone did not switch to recording")
        }
        record = audioRecord
        samples = ShortArray(MAX_SECONDS * SAMPLE_RATE)
        filled = 0

        captureJob = scope.launch(Dispatchers.IO) {
            val chunk = bufferSize.coerceAtLeast(MIN_CHUNK)
            while (isActive) {
                val room = samples.size - filled
                if (room <= 0) {
                    onLimitReached?.invoke()
                    break
                }
                val read = audioRecord.read(samples, filled, minOf(chunk, room))
                when {
                    read > 0 -> filled += read
                    read == 0 -> delay(EMPTY_READ_DELAY_MS)
                    else -> {
                        AppLog.e(TAG, "Lecture du micro en échec (code $read) : capture arrêtée")
                        onMicLost?.invoke()
                        break
                    }
                }
            }
        }
    }

    /** Arrête la capture, libère le micro et renvoie ce qui a été enregistré (vide si rien n'était en cours). */
    suspend fun stop(): ShortArray {
        captureJob?.cancelAndJoin()
        captureJob = null
        releaseMic()
        val result = samples.copyOf(filled)
        samples = ShortArray(0)
        filled = 0
        return result
    }

    /** Abandonne l'enregistrement : le micro est libéré, l'audio est jeté. */
    fun cancel() {
        captureJob?.cancel()
        captureJob = null
        releaseMic()
        samples = ShortArray(0)
        filled = 0
    }

    private fun releaseMic() {
        record?.let { audioRecord ->
            try {
                audioRecord.stop()
            } catch (e: IllegalStateException) {
                AppLog.w(TAG, "Arrêt du micro impossible (déjà perdu ?)", e)
            } finally {
                audioRecord.release()
            }
        }
        record = null
    }

    companion object {
        private const val TAG = "AiTranscriptionRecorder"
        const val SAMPLE_RATE = 16_000

        /** Durée maximale enregistrée : 2 minutes, validée au spike 14.1 (E2B : 120 s transcrites en 51 s, RAM stable). */
        const val MAX_SECONDS = 120
        private const val MIN_CHUNK = 1_600
        private const val EMPTY_READ_DELAY_MS = 10L
    }
}
