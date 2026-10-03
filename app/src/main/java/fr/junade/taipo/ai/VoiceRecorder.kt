package fr.junade.taipo.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineStream
import fr.junade.taipo.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Raison pour laquelle la capture s'est arrêtée d'elle-même, hors silence prolongé. */
enum class CaptureError {
    /** Le micro ne fournit plus de données (erreur de lecture, micro repris par le système ou une autre application). */
    MIC_LOST,

    /** Le décodage a pris trop de retard sur la voix : la file de capture est pleine. */
    BACKLOG_OVERFLOW,
}

/** Le micro n'a pas pu être ouvert (non initialisé, occupé, démarrage refusé). */
class MicUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

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
 * câblée dans VoiceController).
 *
 * Robustesse (lot 2.5 de la revue de code) : [start] lève [MicUnavailableException] si le micro ne
 * s'ouvre pas (sans rien laisser d'alloué) ; en cours d'écoute, une lecture en erreur ou une file de
 * décodage saturée arrêtent la capture proprement et sont signalées par [onCaptureError].
 */
class VoiceRecorder(private val engine: VoiceEngine, private val scope: CoroutineScope) {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var decodeJob: Job? = null
    private var audioChannel: Channel<FloatArray>? = null
    private var stream: OnlineStream? = null

    /** Appelé (sur un thread d'arrière-plan) si 20s de silence continu sont détectées. */
    var onSilenceTimeout: (() -> Unit)? = null

    /**
     * Appelé (sur un thread d'arrière-plan) quand la capture s'arrête d'elle-même pour une erreur. La
     * capture est déjà terminée à ce moment-là : l'appelant doit encore appeler [stopAndGetResult] pour
     * récupérer le texte déjà transcrit et libérer le micro.
     */
    var onCaptureError: ((CaptureError) -> Unit)? = null

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
        // Micro non initialisé (permission retirée entre-temps, micro indisponible) : on libère et on le dit.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicUnavailableException("AudioRecord non initialisé (état ${record.state})")
        }
        val voiceStream = engine.createStream()
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            abortStart(record, voiceStream)
            throw MicUnavailableException("Démarrage du micro refusé", e)
        }
        // startRecording() ne lève pas toujours d'exception quand le micro est pris ailleurs.
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            abortStart(record, voiceStream)
            throw MicUnavailableException("Le micro n'est pas passé en enregistrement")
        }
        audioRecord = record
        stream = voiceStream
        _partialText.value = ""

        // File large mais bornée (voir CaptureBacklog) entre capture et décodage : si decodeAvailable()
        // met du temps (typiquement son tout premier appel, avec le coût d'initialisation du graphe
        // ONNX), la capture continue à vider le micro sans attendre, au lieu de laisser le petit buffer
        // natif d'AudioRecord déborder et perdre le tout début de la phrase (bug observé sur les
        // enregistrements un peu longs, retour du 26/09/2026). La file est de ~60 s d'audio : bien
        // au-delà de tout retard normal. Politique en cas de saturation : la capture s'arrête avec un
        // message (BACKLOG_OVERFLOW) ; on ne supprime jamais d'audio en silence, ce qui laisserait des
        // trous dans la transcription.
        val channel = Channel<FloatArray>(capacity = CaptureBacklog.capacityFor(bufferSize, SAMPLE_RATE))
        audioChannel = channel

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize)
            val monitor = ReadMonitor()
            var silenceStartAt = -1L
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                when (monitor.onRead(read)) {
                    ReadVerdict.FAILED -> {
                        AppLog.e(TAG, "Lecture du micro en échec (code $read) : capture arrêtée")
                        onCaptureError?.invoke(CaptureError.MIC_LOST)
                        break
                    }
                    ReadVerdict.WAIT -> {
                        delay(ReadMonitor.EMPTY_READ_DELAY_MS)
                        continue
                    }
                    ReadVerdict.DATA -> Unit
                }

                val samples = FloatArray(read) { i -> buffer[i] / 32768.0f }
                val sent = channel.trySend(samples)
                if (sent.isFailure) {
                    // File fermée par stopAndGetResult() : fin normale. Sinon, file pleine : saturation.
                    if (!sent.isClosed) {
                        AppLog.e(TAG, "File de décodage saturée : capture arrêtée")
                        onCaptureError?.invoke(CaptureError.BACKLOG_OVERFLOW)
                    }
                    break
                }

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
        audioRecord?.let { record ->
            // stop() lève IllegalStateException si le micro est déjà mort : on libère quoi qu'il arrive
            // et on garde le texte déjà transcrit.
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                AppLog.w(TAG, "Arrêt du micro impossible (déjà perdu ?)", e)
            } finally {
                record.release()
            }
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

    /** Libère ce que [start] avait déjà alloué quand le micro refuse de démarrer. */
    private fun abortStart(record: AudioRecord, voiceStream: OnlineStream) {
        record.release()
        voiceStream.release()
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
        private const val TAG = "VoiceRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val SILENCE_RMS_THRESHOLD = 500.0
        private const val SILENCE_TIMEOUT_MS = 20_000L
    }
}
