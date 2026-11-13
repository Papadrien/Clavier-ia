package fr.junade.taipo.ai

import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import fr.junade.taipo.AppLog
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Transcription IA par modèle multimodal (épopée 14) : envoie un enregistrement audio à Gemma 4 (E2B « Équilibré » ou
 * E4B « Performant ») via le moteur LiteRT-LM partagé [host], et renvoie le texte.
 *
 * Choix issus du spike 14.1 (validé le 11/10/2026 sur Pixel 9) : `Content.AudioBytes` avec un WAV PCM 16 bits, 16 kHz,
 * mono ; moteur chargé avec `audioBackend = Backend.CPU()` (voir [LlmEngineHost.ensureLoaded], `withAudio`). La forme
 * `Content.AudioFile` du banc de mesure n'est pas reprise : l'audio ne touche jamais le disque.
 *
 * Annulation : comme [CorrectionEngine], [cancel] demande un arrêt natif (`cancelProcess`), puis la conversation n'est
 * fermée qu'après un court délai (un arrêt natif suivi d'une fermeture immédiate avait fait planter le moteur).
 * Prompt : [TranscriptionPrompt] (nettoyage et reformulation, ton conservé), modifiable via [TranscriptionPromptPreferences].
 * Ni l'audio ni le texte ne sont écrits dans les journaux (story 14.6) : seulement des durées et des tailles.
 *
 * Gemma 3 (sans entrée audio, décision du 13/11/2026) : [dictate] passe l'enregistrement à la dictée (sherpa-onnx, moteur
 * [voiceEngine] partagé avec le bouton Vocal), puis [rewrite] fait reformuler le texte brut par le modèle de texte
 * ([TranscriptionPrompt.DICTATION_SYSTEM]), morceau par morceau ; une réponse écartée par [DictationRewrite.accept] laisse
 * le texte brut de ce morceau.
 */
class AiTranscriptionEngine(
    private val host: LlmEngineHost,
    private val promptPreferences: TranscriptionPromptPreferences,
    private val voiceEngine: () -> VoiceEngine,
) {

    /** Conversation dont l'inférence tourne en ce moment (null hors inférence) : cible de [cancel]. */
    @Volatile private var runningConversation: Conversation? = null

    /** Un arrêt natif vient d'être demandé : [transcribe] laisse au moteur le temps de finir avant de fermer. */
    @Volatile private var nativeCancelRequested = false

    /**
     * Interrompt l'inférence en cours, sans effet si aucune ne tourne (chargement du modèle compris : l'appelant
     * ignore alors le résultat). Peut bloquer un instant : à appeler hors du thread principal.
     */
    fun cancel() {
        val running = runningConversation ?: return
        nativeCancelRequested = true
        try {
            running.cancelProcess()
        } catch (e: Exception) {
            AppLog.w(TAG, "arrêt de la transcription native", e)
        }
    }

    /**
     * Transcrit [wav] (WAV PCM 16 bits, 16 kHz, mono) avec le modèle [model], qui doit avoir une entrée audio.
     * [onLoading] est appelé si le moteur doit être (re)chargé, avec le backend audio (plus lourd en mémoire).
     * [isCancelled] : vrai si l'utilisateur a annulé ; testé une fois le moteur chargé, avant l'inférence.
     */
    suspend fun transcribe(
        model: AiModel,
        wav: ByteArray,
        audioSeconds: Double,
        isCancelled: () -> Boolean,
        onLoading: () -> Unit,
    ): String = host.inferenceLock.withLock {
        val engine = host.ensureLoaded(model, onLoading, withAudio = true)
        if (isCancelled()) throw CancellationException("Transcription cancelled by the user")
        // Le moteur ne porte qu'une conversation à la fois : on ferme celle de la génération par prompt.
        host.releaseOpenConversation()
        val config = ConversationConfig(systemInstruction = Contents.of(promptPreferences.get()))
        val startedAt = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) {
            engine.createConversation(config).use { conversation ->
                nativeCancelRequested = false
                runningConversation = conversation
                try {
                    val text = StringBuilder()
                    conversation.sendMessageAsync(Contents.of(Content.AudioBytes(wav), Content.Text(TranscriptionPrompt.USER)))
                        .collect { message -> text.append(message.toString()) }
                    AppLog.i(
                        TAG,
                        "transcription: modèle=${model.id} audio=${"%.1f".format(audioSeconds)} s " +
                            "durée=${SystemClock.elapsedRealtime() - startedAt} ms sortie=${text.trim().length} car.",
                    )
                    text.toString().trim()
                } finally {
                    runningConversation = null
                    if (nativeCancelRequested) {
                        // Comme pour la correction et la génération (SIGSEGV du 06/10/2026) : après un arrêt natif, on
                        // laisse le moteur finir de remonter sa pile avant de fermer la conversation.
                        withContext(NonCancellable) { delay(NATIVE_GRACE_MS) }
                        nativeCancelRequested = false
                    }
                }
            }
        }
    }

    /**
     * Dictée de [samples] (PCM 16 bits, 16 kHz, mono) par le modèle vocal : texte brut, sans reformulation. Le modèle vocal
     * doit être installé (vérifié par l'appelant). [isCancelled] est testé entre deux blocs d'audio.
     */
    suspend fun dictate(samples: ShortArray, isCancelled: () -> Boolean): String {
        val voice = voiceEngine()
        val startedAt = SystemClock.elapsedRealtime()
        voice.ensureLoaded()
        return withContext(Dispatchers.Default) {
            // Le moteur peut avoir été libéré (mémoire faible) entre le chargement et l'ouverture du flux : on recharge une fois.
            val stream = try {
                voice.createStream()
            } catch (_: IllegalArgumentException) {
                voice.ensureLoaded()
                voice.createStream()
            }
            try {
                var offset = 0
                while (offset < samples.size) {
                    if (isCancelled()) throw CancellationException("Transcription cancelled by the user")
                    val end = minOf(samples.size, offset + DICTATION_BLOCK)
                    val block = FloatArray(end - offset) { samples[offset + it] / PCM_SCALE }
                    voice.accept(stream, block, AiTranscriptionRecorder.SAMPLE_RATE)
                    voice.decodeAvailable(stream)
                    offset = end
                }
                // Un peu de silence en fin d'audio : le modèle en continu rend ainsi les derniers mots.
                voice.accept(stream, FloatArray(TAIL_PADDING), AiTranscriptionRecorder.SAMPLE_RATE)
                voice.finishInput(stream)
                voice.decodeAvailable(stream)
                val text = voice.currentText(stream).trim()
                AppLog.i(
                    TAG,
                    "dictée: audio=${samples.size / AiTranscriptionRecorder.SAMPLE_RATE} s " +
                        "durée=${SystemClock.elapsedRealtime() - startedAt} ms sortie=${text.length} car.",
                )
                text
            } finally {
                voice.releaseStream(stream)
            }
        }
    }

    /**
     * Reformule [text] (sortie brute de [dictate]) avec le modèle de texte [model], par morceaux de
     * [DictationRewrite.MAX_WORDS_PER_CHUNK] mots ; les morceaux sont rejoints par des paragraphes. Un morceau dont la
     * réponse est écartée par [DictationRewrite.accept] reste tel que dicté.
     */
    suspend fun rewrite(model: AiModel, text: String, isCancelled: () -> Boolean, onLoading: () -> Unit): String {
        val parts = DictationRewrite.chunks(text).map { chunk ->
            val rewritten = host.inferenceLock.withLock {
                try {
                    rewriteLocked(model, chunk, isCancelled, onLoading)
                } catch (e: Exception) {
                    // GPU en échec pendant la réponse : une seule relance, sur le processeur central (comme la correction).
                    if (!host.abandonGpuAfter(e)) throw e
                    rewriteLocked(model, chunk, isCancelled, onLoading)
                }
            }
            DictationRewrite.accept(chunk, rewritten) ?: chunk.also { AppLog.w(TAG, "reformulation écartée : texte dicté gardé", null) }
        }
        return parts.joinToString("\n\n")
    }

    /** Une reformulation, avec [LlmEngineHost.inferenceLock] tenu. */
    private suspend fun rewriteLocked(model: AiModel, chunk: String, isCancelled: () -> Boolean, onLoading: () -> Unit): String {
        val engine = host.ensureLoaded(model, onLoading)
        if (isCancelled()) throw CancellationException("Transcription cancelled by the user")
        host.releaseOpenConversation()
        val config = ConversationConfig(systemInstruction = Contents.of(TranscriptionPrompt.DICTATION_SYSTEM))
        val startedAt = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.Default) {
            engine.createConversation(config).use { conversation ->
                nativeCancelRequested = false
                runningConversation = conversation
                try {
                    val result = conversation.sendMessage(TranscriptionPrompt.dictationUserTurn(chunk)).toString().trim()
                    AppLog.i(
                        TAG,
                        "reformulation: modèle=${model.id} entrée=${chunk.length} car. sortie=${result.length} car. " +
                            "durée=${SystemClock.elapsedRealtime() - startedAt} ms",
                    )
                    result
                } finally {
                    runningConversation = null
                    if (nativeCancelRequested) {
                        withContext(NonCancellable) { delay(NATIVE_GRACE_MS) }
                        nativeCancelRequested = false
                    }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "AiTranscriptionEngine"

        /** Pause entre la fin d'une inférence arrêtée et la fermeture de sa conversation native. */
        private const val NATIVE_GRACE_MS = 500L

        /** Audio donné à la dictée par blocs d'une seconde, avec un décodage entre deux. */
        private const val DICTATION_BLOCK = AiTranscriptionRecorder.SAMPLE_RATE

        /** 0,5 s de silence ajoutée en fin d'audio. */
        private const val TAIL_PADDING = AiTranscriptionRecorder.SAMPLE_RATE / 2

        /** PCM 16 bits vers flottants -1..1, le format que demande sherpa-onnx. */
        private const val PCM_SCALE = 32_768f
    }
}
