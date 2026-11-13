package fr.junade.taipo.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import fr.junade.taipo.AppLog
import fr.junade.taipo.VoiceLevel
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
 * processus, et [stop] comme [cancel] le jettent. Gemma 3 passe par la dictée (13/11/2026) : sa limite est
 * [DICTATION_MAX_SECONDS] (le tampon grandit au fur et à mesure). Distinct de [VoiceRecorder], qui alimente le décodeur en continu et
 * n'est donc pas réutilisable tel quel.
 *
 * L'appelant vérifie la permission RECORD_AUDIO avant [start]. Toutes les méthodes se rappellent depuis le thread principal.
 */
class AiTranscriptionRecorder(context: Context, private val scope: CoroutineScope) {

    private val audioFocus = RecordingAudioFocus(context)

    private var record: AudioRecord? = null
    private var captureJob: Job? = null
    private var samples = ShortArray(0)

    @Volatile private var filled = 0

    /** Appelé (thread d'arrière-plan) quand la durée maximale est atteinte : la capture est alors terminée. */
    var onLimitReached: (() -> Unit)? = null

    /** Appelé (thread d'arrière-plan) quand le micro ne fournit plus de données : la capture est alors terminée. */
    var onMicLost: (() -> Unit)? = null

    /** Appelé (thread d'arrière-plan) à chaque bloc lu, avec le niveau sonore de 0 à 1 : il anime l'icône du bouton. */
    var onLevel: ((Float) -> Unit)? = null

    val isRecording: Boolean get() = record != null

    /** Durée maximale de l'enregistrement en cours, en échantillons. */
    private var maxSamples = MAX_SECONDS * SAMPLE_RATE

    /** [maxSeconds] : durée maximale ([MAX_SECONDS] pour l'audio natif de Gemma 4, [DICTATION_MAX_SECONDS] pour la dictée). */
    @Suppress("MissingPermission") // Vérifié par l'appelant avant d'appeler start().
    fun start(maxSeconds: Int = MAX_SECONDS) {
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
        // Met en pause la lecture audio des autres applications avant d'ouvrir le micro.
        audioFocus.acquire()
        try {
            audioRecord.startRecording()
        } catch (e: IllegalStateException) {
            audioRecord.release()
            audioFocus.release()
            throw MicUnavailableException("Microphone start refused", e)
        }
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.release()
            audioFocus.release()
            throw MicUnavailableException("The microphone did not switch to recording")
        }
        record = audioRecord
        maxSamples = maxSeconds * SAMPLE_RATE
        samples = ShortArray(minOf(maxSamples, INITIAL_SECONDS * SAMPLE_RATE))
        filled = 0

        captureJob = scope.launch(Dispatchers.IO) {
            val chunk = bufferSize.coerceAtLeast(MIN_CHUNK)
            while (isActive) {
                val room = roomLeft()
                if (room <= 0) {
                    onLimitReached?.invoke()
                    break
                }
                val read = audioRecord.read(samples, filled, minOf(chunk, room))
                when {
                    read > 0 -> {
                        onLevel?.invoke(VoiceLevel.fromRms(rms(samples, filled, read)))
                        filled += read
                    }
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

    /** Place libre dans le tampon ; un tampon plein est d'abord agrandi (doublé) tant que la durée maximale le permet. */
    private fun roomLeft(): Int {
        if (filled == samples.size && samples.size < maxSamples) samples = samples.copyOf(minOf(maxSamples, samples.size * 2))
        return samples.size - filled
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

    private fun rms(buffer: ShortArray, from: Int, length: Int): Double {
        var sum = 0.0
        for (i in from until from + length) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return kotlin.math.sqrt(sum / length)
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
        audioFocus.release()
    }

    companion object {
        private const val TAG = "AiTranscriptionRecorder"
        const val SAMPLE_RATE = 16_000

        /** Durée maximale enregistrée : 2 minutes, validée au spike 14.1 (E2B : 120 s transcrites en 51 s, RAM stable). */
        const val MAX_SECONDS = 120

        /** Gemma 3 (dictée puis reformulation) : 10 minutes, environ 19 Mo d'audio en mémoire au plus. */
        const val DICTATION_MAX_SECONDS = 600

        /** Taille de départ du tampon : 2 minutes, doublée au besoin jusqu'à la limite. */
        private const val INITIAL_SECONDS = 120
        private const val MIN_CHUNK = 1_600
        private const val EMPTY_READ_DELAY_MS = 10L
    }
}
