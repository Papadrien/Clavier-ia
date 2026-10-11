package fr.junade.taipo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import fr.junade.taipo.ai.AudioSpikeEngine
import fr.junade.taipo.ai.AudioSpikeRecorder
import fr.junade.taipo.ai.AudioSpikeReport
import fr.junade.taipo.ai.MicUnavailableException
import fr.junade.taipo.ai.WavEncoder
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Spike 14.1 (build debug seulement) : un appui enregistre, un second appui arrête et envoie l'audio au modèle actif
 * (Gemma 4), puis affiche un compte rendu (durées, mémoire, texte) et insère le texte au curseur. Aucune annulation de la
 * transcription en cours : c'est un banc de mesure, pas la fonction finale (stories 14.2 à 14.6).
 *
 * L'audio n'existe que le temps du traitement (fichier temporaire du cache, supprimé ensuite) ; ni l'audio ni le texte ne
 * sont écrits dans les journaux. Toutes les méthodes sont à appeler depuis le thread principal.
 */
class AudioSpikeController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: AudioSpikeEngine,
    private val host: Host,
) {

    /** Ce que le spike demande à l'IME. */
    interface Host {
        fun showMessage(message: String)

        /** Une dictée est en cours (le micro est pris). */
        fun voiceRecording(): Boolean

        /** Une correction ou une génération est en cours (le moteur est pris). */
        fun aiBusy(): Boolean

        fun activeModel(): AiModel?

        /** Insère [text] au curseur ; faux si le champ n'est pas accessible. */
        fun insertText(text: String): Boolean

        fun setBarState(state: AudioSpikeBarState)
    }

    private val recorder = AudioSpikeRecorder(scope)
    private var job: Job? = null
    private var state = AudioSpikeBarState.IDLE

    /** Faux dès que le champ de saisie a changé pendant le traitement : le texte n'est plus inséré (il reste au compte rendu). */
    private var insertAllowed = true

    init {
        // Limite de durée atteinte ou micro perdu : même chose qu'un second appui.
        recorder.onLimitReached = { scope.launch { if (state == AudioSpikeBarState.RECORDING) stopAndTranscribe() } }
        recorder.onMicLost = { scope.launch { if (state == AudioSpikeBarState.RECORDING) stopAndTranscribe() } }
    }

    fun onClick() {
        when (state) {
            AudioSpikeBarState.IDLE -> start()
            AudioSpikeBarState.RECORDING -> stopAndTranscribe()
            AudioSpikeBarState.PROCESSING -> host.showMessage(context.getString(R.string.audio_spike_processing))
        }
    }

    /** Fin de saisie dans le champ (clavier fermé ou champ changé) : l'enregistrement est abandonné ; un traitement en cours n'insérera plus rien. */
    fun onInputFinished() {
        when (state) {
            AudioSpikeBarState.RECORDING -> {
                recorder.cancel()
                setState(AudioSpikeBarState.IDLE)
            }
            AudioSpikeBarState.PROCESSING -> insertAllowed = false
            AudioSpikeBarState.IDLE -> Unit
        }
    }

    /** Destruction du service : micro libéré, traitement interrompu côté Kotlin. */
    fun release() {
        recorder.cancel()
        job?.cancel()
    }

    private fun start() {
        if (host.voiceRecording() || host.aiBusy()) {
            host.showMessage(context.getString(R.string.audio_spike_busy))
            return
        }
        val model = host.activeModel()
        if (model == null || !model.hasAudioInput) {
            host.showMessage(context.getString(R.string.audio_spike_no_audio_model))
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.showMessage(context.getString(R.string.audio_spike_permission_denied))
            context.startActivity(Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // Reste d'un traitement tué en cours de route.
        tempFile().delete()
        try {
            recorder.start()
        } catch (e: MicUnavailableException) {
            AppLog.w(TAG, "micro indisponible", e)
            host.showMessage(context.getString(R.string.audio_spike_mic_unavailable))
            return
        }
        insertAllowed = true
        setState(AudioSpikeBarState.RECORDING)
    }

    private fun stopAndTranscribe() {
        val model = host.activeModel()
        setState(AudioSpikeBarState.PROCESSING)
        job = scope.launch {
            val wavFile = tempFile()
            try {
                val samples = recorder.stop()
                if (samples.size < MIN_SAMPLES) {
                    host.showMessage(context.getString(R.string.audio_spike_too_short))
                    return@launch
                }
                if (model == null || !model.hasAudioInput) {
                    host.showMessage(context.getString(R.string.audio_spike_no_audio_model))
                    return@launch
                }
                val seconds = samples.size.toDouble() / AudioSpikeRecorder.SAMPLE_RATE
                val wav = WavEncoder.pcm16Mono(samples, AudioSpikeRecorder.SAMPLE_RATE)
                withContext(Dispatchers.IO) {
                    wavFile.parentFile?.mkdirs()
                    wavFile.writeBytes(wav)
                }
                val measures = engine.transcribe(model, wav, wavFile, seconds, onLoading = { })
                // Diagnostic : jamais l'audio ni le texte, seulement des durées et des tailles (story 14.6).
                AppLog.i(
                    TAG,
                    "spike 14.1: modèle=${model.id} audio=${"%.1f".format(seconds)} s api=${measures.apiUsed} " +
                        "chargement=${measures.loadMs} ms premier=${measures.firstChunkMs} ms inférence=${measures.inferenceMs} ms " +
                        "ram=${measures.ramBeforeMb}->${measures.ramPeakMb} Mo sortie=${measures.text.trim().length} car.",
                )
                val text = measures.text.trim()
                if (text.isNotEmpty() && insertAllowed) host.insertText(text)
                host.showMessage(AudioSpikeReport.format(measures))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "spike 14.1 en échec", e)
                host.showMessage("Spike 14.1 — échec : ${e::class.java.simpleName} : ${e.message}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { wavFile.delete() }
                setState(AudioSpikeBarState.IDLE)
            }
        }
    }

    private fun tempFile() = File(File(context.cacheDir, "spike14"), "last.wav")

    private fun setState(newState: AudioSpikeBarState) {
        state = newState
        host.setBarState(newState)
    }

    private companion object {
        private const val TAG = "AudioSpikeController"

        /** Une demi-seconde d'audio à 16 kHz : en dessous, rien d'exploitable. */
        private const val MIN_SAMPLES = AudioSpikeRecorder.SAMPLE_RATE / 2
    }
}
