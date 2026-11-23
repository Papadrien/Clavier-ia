package fr.junade.taipo.ai

import android.os.SystemClock
import fr.junade.taipo.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Transcription IA en direct (23/11/2026, d'après Typeless) : au lieu d'attendre la fin de l'enregistrement pour dicter puis
 * reformuler tout le texte, le modèle vocal écrit pendant que l'utilisateur parle, et chaque bloc terminé ([LiveRewrite]) part
 * aussitôt en reformulation ([rewriteChunk]). À l'arrêt ([finish]), il ne reste que la fin du dernier bloc : le temps d'attente
 * ne grandit plus avec la durée de la dictée. Le premier bloc charge aussi le modèle de texte pendant l'écoute.
 *
 * Deux tâches : la dictée (fil de calcul, flux sherpa-onnx alimenté par [offer]) et la reformulation (blocs dans l'ordre, un à la
 * fois, sous le verrou d'inférence du moteur). Ni l'audio ni le texte ne sont journalisés.
 */
class LiveDictation(
    private val scope: CoroutineScope,
    private val voice: () -> VoiceEngine,
    private val rewriteChunk: suspend (chunk: String, previous: String) -> String,
) {

    private val audio = Channel<ShortArray>(Channel.UNLIMITED)
    private val blocks = Channel<String>(Channel.UNLIMITED)

    /** Blocs déjà reformulés, dans l'ordre (écrits par la seule tâche de reformulation, lus après sa fin). */
    private val rewritten = ArrayList<String>()

    private var dictationJob: Job? = null
    private var rewriteJob: Job? = null

    @Volatile private var failure: Throwable? = null

    /** Texte brut dicté (pour le journal de debug : sa longueur seulement). */
    @Volatile private var rawLength = 0

    /** Lance la dictée et la reformulation : l'audio donné par [offer] avant le chargement du modèle vocal attend son tour. */
    @Suppress("TooGenericExceptionCaught") // échec du modèle de texte : rapporté par finish()
    fun start() {
        dictationJob = scope.launch(Dispatchers.Default) { dictate() }
        rewriteJob = scope.launch {
            try {
                for (block in blocks) rewritten += rewriteChunk(block, LiveRewrite.join(rewritten))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failure = failure ?: t
                blocks.cancel()
            }
        }
    }

    /** Bloc d'audio PCM 16 bits, 16 kHz, mono (appelé depuis le fil de capture). */
    fun offer(samples: ShortArray) {
        audio.trySend(samples)
    }

    /**
     * Fin de l'écoute : la dictée termine avec ce qu'elle a, la fin du texte est reformulée, puis le tout est rendu (vide : rien
     * n'a été dit). Lève l'erreur de la dictée ou de la reformulation, s'il y en a eu une.
     */
    suspend fun finish(): String {
        val startedAt = SystemClock.elapsedRealtime()
        audio.close()
        dictationJob?.join()
        rewriteJob?.join()
        failure?.let { throw it }
        val text = LiveRewrite.join(rewritten)
        AppLog.i(
            TAG,
            "transcription en direct : attente après l'arrêt=${SystemClock.elapsedRealtime() - startedAt} ms " +
                "dicté=$rawLength car. blocs=${rewritten.size} sortie=${text.length} car.",
        )
        return text
    }

    /** Abandon (annulation, champ changé) : les deux tâches s'arrêtent, rien n'est rendu. */
    fun cancel() {
        audio.close()
        blocks.cancel()
        dictationJob?.cancel()
        rewriteJob?.cancel()
    }

    @Suppress("TooGenericExceptionCaught") // échec natif ou modèle vocal absent : rapporté par finish()
    private suspend fun dictate() {
        val engine = voice()
        try {
            engine.ensureLoaded()
            val stream = try {
                engine.createStream()
            } catch (_: IllegalArgumentException) {
                engine.ensureLoaded() // moteur libéré (mémoire faible) entre le chargement et l'ouverture du flux
                engine.createStream()
            }
            try {
                feed(engine, stream)
            } finally {
                engine.releaseStream(stream)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failure = failure ?: t
        } finally {
            blocks.close()
        }
    }

    /** Donne l'audio au flux au fil de l'eau ; chaque bloc terminé part en reformulation, puis la fin à l'arrêt. */
    private suspend fun feed(engine: VoiceEngine, stream: com.k2fsa.sherpa.onnx.OnlineStream) {
        var consumed = 0
        for (samples in audio) {
            engine.accept(stream, FloatArray(samples.size) { samples[it] / PCM_SCALE }, AiTranscriptionRecorder.SAMPLE_RATE)
            engine.decodeAvailable(stream)
            consumed = sendCompleteBlocks(engine.currentText(stream), consumed)
        }
        // Un peu de silence en fin d'audio : le modèle en continu rend ainsi les derniers mots.
        engine.accept(stream, FloatArray(TAIL_PADDING), AiTranscriptionRecorder.SAMPLE_RATE)
        engine.finishInput(stream)
        engine.decodeAvailable(stream)
        val text = engine.currentText(stream).trim()
        rawLength = text.length
        val rest = LiveRewrite.rest(text, consumed)
        if (rest.isNotBlank()) blocks.send(rest)
    }

    /** Envoie les blocs complets de [text] après ses [consumed] premiers mots ; renvoie le nouveau nombre de mots envoyés. */
    private suspend fun sendCompleteBlocks(text: String, consumed: Int): Int {
        var sent = consumed
        while (true) {
            val block = LiveRewrite.nextBlock(text, sent) ?: return sent
            blocks.send(block.text)
            sent += block.words
        }
    }

    private companion object {
        const val TAG = "LiveDictation"

        /** 0,5 s de silence ajoutée en fin d'audio. */
        const val TAIL_PADDING = AiTranscriptionRecorder.SAMPLE_RATE / 2

        /** PCM 16 bits vers flottants -1..1, le format que demande sherpa-onnx. */
        const val PCM_SCALE = 32_768f
    }
}
