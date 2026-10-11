package fr.junade.taipo.ai

import android.os.Debug
import android.os.SystemClock
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcription par modèle multimodal, version spike 14.1 (build debug seulement) : envoie un enregistrement audio à
 * Gemma 4 (E2B ou E4B) via le moteur LiteRT-LM partagé [host] et mesure durée et mémoire.
 *
 * API utilisée, d'après la documentation officielle Kotlin (developers.google.com/edge/litert-lm/android) :
 * `EngineConfig(audioBackend = Backend.CPU())`, `Content.AudioBytes(ByteArray)` / `Content.AudioFile(chemin)`,
 * `Conversation.sendMessageAsync(Contents): Flow<Message>`. NON vérifié : que ces classes existent dans litertlm-android
 * 0.17.1 (version figée du projet) et quel format de fichier audio elles acceptent ; le build et l'appareil le diront.
 * Les deux formes (octets, puis fichier) sont essayées dans cet ordre, et celle qui a marché est indiquée au compte rendu.
 */
class AudioSpikeEngine(private val host: LlmEngineHost) {

    private class Attempt(val text: String, val firstChunkMs: Long?, val totalMs: Long)

    private enum class Form(val label: String) {
        BYTES("Content.AudioBytes"),
        FILE("Content.AudioFile"),
    }

    /**
     * Transcrit [wav] (aussi écrit dans [wavFile], pour la forme « fichier »). [onLoading] est appelé si le moteur doit
     * être (re)chargé avec le backend audio. Lève l'erreur de la dernière forme essayée si les deux échouent.
     */
    suspend fun transcribe(
        model: AiModel,
        wav: ByteArray,
        wavFile: File,
        audioSeconds: Double,
        onLoading: () -> Unit,
    ): AudioSpikeMeasures = host.inferenceLock.withLock {
        val probe = MemoryProbe()
        val ramBefore = probe.sample()
        coroutineScope {
            val sampler = launch(Dispatchers.Default) {
                while (isActive) {
                    delay(SAMPLE_PERIOD_MS)
                    probe.sample()
                }
            }
            try {
                var reloaded = false
                val loadStart = SystemClock.elapsedRealtime()
                val engine = host.ensureLoaded(model, onLoading = {
                    reloaded = true
                    onLoading()
                }, withAudio = true)
                val loadMs = if (reloaded) SystemClock.elapsedRealtime() - loadStart else null
                // Le moteur ne porte qu'une conversation à la fois : on ferme celle de la génération par prompt.
                host.releaseOpenConversation()

                val (result, form, firstFailure) = withContext(Dispatchers.Default) {
                    try {
                        Triple(attempt(engine, Form.BYTES, wav, wavFile), Form.BYTES, null as String?)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val reason = "${e::class.java.simpleName}: ${e.message}".take(MAX_REASON_CHARS)
                        Triple(attempt(engine, Form.FILE, wav, wavFile), Form.FILE, reason as String?)
                    }
                }
                probe.sample()
                AudioSpikeMeasures(
                    modelLabel = "${model.technicalName} (${model.displayName})",
                    audioSeconds = audioSeconds,
                    wavBytes = wav.size,
                    apiUsed = form.label,
                    firstApiFailure = firstFailure,
                    loadMs = loadMs,
                    firstChunkMs = result.firstChunkMs,
                    inferenceMs = result.totalMs,
                    ramBeforeMb = ramBefore,
                    ramPeakMb = probe.peakMb,
                    text = result.text,
                )
            } finally {
                sampler.cancel()
            }
        }
    }

    private suspend fun attempt(engine: Engine, form: Form, wav: ByteArray, wavFile: File): Attempt {
        val config = ConversationConfig(systemInstruction = Contents.of(SYSTEM_PROMPT))
        val startedAt = SystemClock.elapsedRealtime()
        var firstChunkMs: Long? = null
        val text = StringBuilder()
        engine.createConversation(config).use { conversation ->
            val audio = when (form) {
                Form.BYTES -> Content.AudioBytes(wav)
                Form.FILE -> Content.AudioFile(wavFile.path)
            }
            conversation.sendMessageAsync(Contents.of(audio, Content.Text(USER_PROMPT))).collect { message ->
                if (firstChunkMs == null) firstChunkMs = SystemClock.elapsedRealtime() - startedAt
                text.append(message.toString())
            }
        }
        return Attempt(text.toString(), firstChunkMs, SystemClock.elapsedRealtime() - startedAt)
    }

    /** Mémoire du processus (PSS, en Mo) : valeur courante et pic des mesures prises depuis la création. */
    private class MemoryProbe {
        private val info = Debug.MemoryInfo()

        @Volatile var peakMb = 0L
            private set

        fun sample(): Long {
            Debug.getMemoryInfo(info)
            val mb = info.totalPss / 1024L
            if (mb > peakMb) peakMb = mb
            return mb
        }
    }

    private companion object {
        const val SAMPLE_PERIOD_MS = 250L
        const val MAX_REASON_CHARS = 160

        // Prompts de travail du spike (rédigés pour ce test, pas ceux de la story 14.4) : à comparer, si la qualité déçoit,
        // avec la structure de prompt de reconnaissance vocale recommandée par la documentation Gemma (non reprise ici).
        const val SYSTEM_PROMPT = "Tu es un moteur de transcription. Tu transcris fidèlement la parole de l'enregistrement " +
            "audio, dans sa langue d'origine, avec la ponctuation. Tu réponds uniquement par la transcription, sans " +
            "commentaire ni traduction. S'il n'y a pas de parole, tu ne réponds rien."
        const val USER_PROMPT = "Transcris cet enregistrement."
    }
}
