package fr.junade.taipo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.annotation.StringRes
import fr.junade.taipo.ai.AiTranscriptionEngine
import fr.junade.taipo.ai.AiTranscriptionRecorder
import fr.junade.taipo.ai.InferenceFailureCause
import fr.junade.taipo.ai.InferenceFailureClassifier
import fr.junade.taipo.ai.MicUnavailableException
import fr.junade.taipo.ai.WavEncoder
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transcription IA (épopée 14, stories 14.2 et 14.5) : un appui enregistre, un second appui arrête et envoie l'audio au
 * modèle actif (Gemma 4 Équilibré ou Performant) ; le texte obtenu est inséré au curseur, sans aperçu. Pendant le
 * traitement, un nouvel appui annule : rien n'est inséré. L'enregistrement dure 2 minutes au plus (décision du spike 14.1).
 *
 * L'audio n'existe que dans la mémoire du processus, le temps de l'enregistrement et du traitement : aucun fichier,
 * donc rien à supprimer après une annulation, une erreur ou un processus tué. Ni l'audio ni le texte ne sont écrits dans
 * les journaux. Toutes les méthodes sont à appeler depuis le thread principal.
 */
class AiTranscriptionController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: AiTranscriptionEngine,
    private val host: Host,
) {

    /** Ce que la transcription demande à l'IME. */
    interface Host {
        fun showMessage(message: String)

        /** Retour haptique d'une action IA (début, fin d'enregistrement, fin de traitement). */
        fun hapticAi(cue: AiHapticCue)

        /** Une dictée est en cours (le micro est pris). */
        fun voiceRecording(): Boolean

        /** Une correction ou une génération est en cours (le moteur est pris). */
        fun aiBusy(): Boolean

        fun activeModel(): AiModel?

        /** Insère [text] au curseur ; faux si le champ n'est pas accessible. */
        fun insertText(text: String): Boolean

        fun setBarState(state: AiTranscriptionBarState)

        /** Niveau sonore (0 à 1) pendant l'enregistrement : il fait monter et descendre les barres de l'icône. */
        fun setLevel(level: Float)

        /** Joue un son de la transcription IA (démarrage, fin d'écoute, fin de traitement). */
        fun playSound(sound: KeySound)
    }

    private val recorder = AiTranscriptionRecorder(scope)
    private var job: Job? = null
    private var state = AiTranscriptionBarState.IDLE

    /** Faux dès que le champ de saisie a changé pendant le traitement : le texte n'est plus inséré. */
    private var insertAllowed = true

    /** Vrai dès que l'utilisateur a annulé le traitement : le résultat éventuel est jeté. */
    @Volatile private var cancelled = false

    init {
        // Limite de durée atteinte ou micro perdu : même chose qu'un second appui.
        recorder.onLimitReached = { scope.launch { if (state == AiTranscriptionBarState.RECORDING) stopAndTranscribe() } }
        recorder.onLevel = { level -> scope.launch { if (state == AiTranscriptionBarState.RECORDING) host.setLevel(level) } }
        recorder.onMicLost = { scope.launch { if (state == AiTranscriptionBarState.RECORDING) stopAndTranscribe() } }
    }

    fun onClick() {
        when (state) {
            AiTranscriptionBarState.IDLE -> start()
            AiTranscriptionBarState.RECORDING -> stopAndTranscribe()
            AiTranscriptionBarState.PROCESSING -> cancelProcessing()
            AiTranscriptionBarState.CANCELLING -> host.showMessage(context.getString(R.string.ai_transcription_cancelling_wait))
        }
    }

    /** Fin de saisie dans le champ (clavier fermé ou champ changé) : l'enregistrement est abandonné ; un traitement en cours n'insérera plus rien. */
    fun onInputFinished() {
        when (state) {
            AiTranscriptionBarState.RECORDING -> {
                recorder.cancel()
                setState(AiTranscriptionBarState.IDLE)
            }
            AiTranscriptionBarState.PROCESSING, AiTranscriptionBarState.CANCELLING -> insertAllowed = false
            AiTranscriptionBarState.IDLE -> Unit
        }
    }

    /** Destruction du service : micro libéré, traitement interrompu côté Kotlin. */
    fun release() {
        recorder.cancel()
        job?.cancel()
    }

    private fun start() {
        if (host.voiceRecording() || host.aiBusy()) {
            host.showMessage(context.getString(R.string.ai_transcription_busy))
            return
        }
        val model = host.activeModel()
        if (model == null || !model.hasAudioInput) {
            host.showMessage(context.getString(R.string.ai_transcription_no_audio_model))
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.showMessage(context.getString(R.string.ai_transcription_permission_denied))
            context.startActivity(Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        try {
            recorder.start()
        } catch (e: MicUnavailableException) {
            AppLog.w(TAG, "micro indisponible", e)
            host.showMessage(context.getString(R.string.ai_transcription_mic_unavailable))
            return
        }
        insertAllowed = true
        cancelled = false
        host.hapticAi(AiHapticCue.ACTION_START)
        host.playSound(KeySound.TRANSCRIPTION_START) // le micro est ouvert : on peut parler
        setState(AiTranscriptionBarState.RECORDING)
    }

    /**
     * Second appui pendant le traitement : l'arrêt natif est demandé hors du thread principal (il peut bloquer un
     * instant), le bouton passe en « annulation » et le texte éventuel sera jeté.
     */
    private fun cancelProcessing() {
        cancelled = true
        setState(AiTranscriptionBarState.CANCELLING)
        scope.launch(Dispatchers.Default) { engine.cancel() }
    }

    private fun stopAndTranscribe() {
        // Fin de l'écoute : appui, limite de 2 minutes atteinte ou micro perdu, tous les cas vibrent pareil.
        host.hapticAi(AiHapticCue.TRANSCRIPTION_STOP)
        host.playSound(KeySound.TRANSCRIPTION_LISTEN_END)
        val model = host.activeModel()
        setState(AiTranscriptionBarState.PROCESSING)
        val startedAt = SystemClock.elapsedRealtime()
        job = scope.launch {
            try {
                val samples = recorder.stop()
                if (samples.size < MIN_SAMPLES) {
                    host.showMessage(context.getString(R.string.ai_transcription_too_short))
                    return@launch
                }
                if (model == null || !model.hasAudioInput) {
                    host.showMessage(context.getString(R.string.ai_transcription_no_audio_model))
                    return@launch
                }
                val seconds = samples.size.toDouble() / AiTranscriptionRecorder.SAMPLE_RATE
                val wav = withContext(Dispatchers.Default) { WavEncoder.pcm16Mono(samples, AiTranscriptionRecorder.SAMPLE_RATE) }
                val text = engine.transcribe(model, wav, seconds, isCancelled = { cancelled }, onLoading = { })
                if (cancelled) return@launch
                when {
                    text.isEmpty() -> host.showMessage(context.getString(R.string.ai_transcription_no_speech))
                    insertAllowed -> {
                        host.insertText(text)
                        host.hapticAi(AiHapticCue.PROCESSING_DONE) // fin du traitement : texte inséré
                        host.playSound(KeySound.TRANSCRIPTION_DONE)
                        Analytics.aiAction(context, AnalyticsEvents.AiType.TRANSCRIBE, model.id, startedAt)
                    }
                }
            } catch (e: CancellationException) {
                // Annulation par l'utilisateur ou destruction du service : rien à afficher ni à insérer.
                if (!cancelled) throw e
            } catch (e: Exception) {
                // Diagnostic : la trace seulement, jamais l'audio ni le texte (story 14.6). L'utilisateur voit une phrase
                // selon la cause (story 8.6), jamais un nom de classe ni le message brut de l'exception.
                AppLog.e(TAG, "transcription en échec", e)
                if (!cancelled) {
                    val cause = InferenceFailureClassifier.classify(e)
                    Analytics.aiAction(context, AnalyticsEvents.AiType.TRANSCRIBE, model?.id, startedAt, failure = cause)
                    host.showMessage(context.getString(failureMessage(cause)))
                }
            } finally {
                setState(AiTranscriptionBarState.IDLE)
            }
        }
    }

    @StringRes
    private fun failureMessage(cause: InferenceFailureCause): Int = when (cause) {
        InferenceFailureCause.MODEL_FILE -> R.string.ai_transcription_error_model_file
        InferenceFailureCause.OUT_OF_MEMORY -> R.string.ai_transcription_error_memory
        InferenceFailureCause.MODEL_INCOMPATIBLE -> R.string.ai_transcription_error_model_incompatible
        InferenceFailureCause.TEXT_TOO_LONG -> R.string.ai_transcription_error_too_long
        InferenceFailureCause.UNEXPECTED -> R.string.ai_transcription_failed
    }

    private fun setState(newState: AiTranscriptionBarState) {
        state = newState
        host.setBarState(newState)
    }

    private companion object {
        private const val TAG = "AiTranscriptionController"

        /** Une demi-seconde d'audio à 16 kHz : en dessous, rien d'exploitable. */
        private const val MIN_SAMPLES = AiTranscriptionRecorder.SAMPLE_RATE / 2
    }
}
