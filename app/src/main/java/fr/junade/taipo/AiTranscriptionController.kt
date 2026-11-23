package fr.junade.taipo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.annotation.StringRes
import fr.junade.taipo.ai.AiTranscriptionEngine
import fr.junade.taipo.ai.AiTranscriptionRecorder
import fr.junade.taipo.ai.DictationContext
import fr.junade.taipo.ai.InferenceFailureCause
import fr.junade.taipo.ai.InferenceFailureClassifier
import fr.junade.taipo.ai.LiveDictation
import fr.junade.taipo.ai.MicUnavailableException
import fr.junade.taipo.ai.WavEncoder
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.VoiceModelPreferences
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
 *
 * Gemma 3 (Fourmi, Colibri), sans entrée audio (13/11/2026) : même bouton et même déroulé, mais l'enregistrement passe par
 * la dictée (modèle vocal) puis le texte est reformulé par le modèle ([AiTranscriptionEngine.dictate] puis
 * [AiTranscriptionEngine.rewrite]) ; jusqu'à 10 minutes d'enregistrement. Sans modèle vocal, l'écran « Modèle vocal » s'ouvre.
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

        /** Contexte de la reformulation : fin du texte avant le curseur et type de champ (lu au début de l'écoute). */
        fun dictationContext(): DictationContext

        /** Mots personnels (dictionnaire de Taipo et du système) : orthographe des noms donnée au modèle. */
        fun personalWords(): List<String>
    }

    private val recorder = AiTranscriptionRecorder(context, scope)
    private val voiceModelPreferences by lazy { VoiceModelPreferences(context.applicationContext) }
    private var job: Job? = null
    private var state = AiTranscriptionBarState.IDLE

    /** Transcription en direct (dictée puis reformulation pendant l'écoute), hors audio natif ; null sinon. */
    private var live: LiveDictation? = null

    /** Le modèle traite l'enregistrement (après l'écoute, avant l'insertion du texte). */
    val isProcessing: Boolean get() = state == AiTranscriptionBarState.PROCESSING

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
                stopLive()
                setState(AiTranscriptionBarState.IDLE)
            }
            AiTranscriptionBarState.PROCESSING, AiTranscriptionBarState.CANCELLING -> insertAllowed = false
            AiTranscriptionBarState.IDLE -> Unit
        }
    }

    /** Destruction du service : micro libéré, traitement interrompu côté Kotlin. */
    fun release() {
        recorder.cancel()
        stopLive()
        job?.cancel()
    }

    private fun stopLive() {
        recorder.onAudio = null
        live?.cancel()
        live = null
    }

    /**
     * Transcription en direct (23/11/2026) : la dictée et la reformulation de chaque bloc terminé tournent pendant l'écoute, avec le
     * contexte du champ (lu maintenant) et les mots personnels proches de chaque bloc.
     */
    private fun startLive(model: AiModel) {
        val fieldContext = host.dictationContext()
        val personal = host.personalWords()
        val session = LiveDictation(scope, engine.voiceEngine) { chunk, previous ->
            val context = fieldContext.continuedBy(previous).copy(names = DictationContext.relevantNames(personal, chunk))
            engine.rewriteChunk(model, chunk, context, isCancelled = { cancelled })
        }
        live = session
        recorder.onAudio = { samples -> session.offer(samples) }
        session.start()
    }

    /**
     * Le modèle traite l'audio lui-même (modèle personnalisé réglé ainsi sur sa page seulement) ; sinon, Gemma 3 et Gemma 4 compris
     * (22/11/2026), dictée avec le modèle vocal puis reformulation du texte. Voir [fr.junade.taipo.ai.TranscriptionRoute].
     */
    private fun nativeAudio(model: AiModel): Boolean =
        fr.junade.taipo.ai.TranscriptionRoute.nativeAudio(model, ModelPreferences(context).acceptsAudio(AiModel.CUSTOM))

    private fun start() {
        if (host.voiceRecording() || host.aiBusy()) {
            host.showMessage(context.getString(R.string.ai_transcription_busy))
            return
        }
        val model = host.activeModel()
        if (model == null) {
            host.showMessage(context.getString(R.string.ai_transcription_no_audio_model))
            return
        }
        if (!nativeAudio(model) && !voiceModelPreferences.isComplete()) {
            // Gemma 3 et Gemma 4 passent par la dictée : sans modèle vocal, on ouvre son écran, comme le bouton Vocal.
            ModelScreenRedirect.openVoice(context)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.showMessage(context.getString(R.string.ai_transcription_permission_denied))
            context.startActivity(Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        insertAllowed = true
        cancelled = false
        if (!nativeAudio(model)) startLive(model) else recorder.onAudio = null
        try {
            recorder.start(if (nativeAudio(model)) AiTranscriptionRecorder.MAX_SECONDS else AiTranscriptionRecorder.DICTATION_MAX_SECONDS)
        } catch (e: MicUnavailableException) {
            AppLog.w(TAG, "micro indisponible", e)
            stopLive()
            host.showMessage(context.getString(R.string.ai_transcription_mic_unavailable))
            return
        }
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
                recorder.onAudio = null
                val session = live
                live = null
                if (samples.size < MIN_SAMPLES) {
                    session?.cancel()
                    host.showMessage(context.getString(R.string.ai_transcription_too_short))
                    return@launch
                }
                if (model == null) {
                    session?.cancel()
                    host.showMessage(context.getString(R.string.ai_transcription_no_audio_model))
                    return@launch
                }
                val text = if (nativeAudio(model)) {
                    val seconds = samples.size.toDouble() / AiTranscriptionRecorder.SAMPLE_RATE
                    val wav = withContext(Dispatchers.Default) { WavEncoder.pcm16Mono(samples, AiTranscriptionRecorder.SAMPLE_RATE) }
                    engine.transcribe(model, wav, seconds, isCancelled = { cancelled }, onLoading = { })
                } else if (session != null) {
                    // Dictée puis reformulation, faites en direct pendant l'écoute : il ne reste que la fin du texte.
                    session.finish()
                } else {
                    // Repli (pas de session en direct) : dictée, puis reformulation du texte brut.
                    val raw = engine.dictate(samples, isCancelled = { cancelled })
                    if (raw.isEmpty() || cancelled) raw else engine.rewrite(model, raw, isCancelled = { cancelled }, onLoading = { })
                }
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
