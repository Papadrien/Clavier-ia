package fr.junade.taipo.ai

import android.os.SystemClock
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

/** La réécriture a été abandonnée par l'utilisateur (panneau refermé, nouvelle frappe) : rien n'est appliqué ni affiché. */
class RewriteCancelledException : CancellationException("Rewrite cancelled")

/**
 * Réécriture d'un texte en un seul tour (changement de ton, traduction : 20/11/2026), sur le moteur LiteRT-LM partagé [host],
 * comme [CorrectionEngine] : même verrou d'inférence, même relance sur le processeur central si le GPU échoue, même arrêt natif.
 * Le prompt système et le tour utilisateur sont fournis par l'appelant ([TonePrompt], [TranslationPrompt]).
 */
class RewriteEngine(private val host: LlmEngineHost) {

    @Volatile private var runningConversation: Conversation? = null

    @Volatile private var nativeCancelRequested = false

    /** Interrompt l'inférence en cours ; sans effet sinon. Peut bloquer un instant : hors du thread principal. */
    @Suppress("TooGenericExceptionCaught") // l'arrêt natif peut échouer de bien des façons : il est seulement journalisé
    fun cancel() {
        val running = runningConversation ?: return
        nativeCancelRequested = true
        try {
            running.cancelProcess()
        } catch (e: Exception) {
            AppLog.w(TAG, "arrêt de la réécriture native", e)
        }
    }

    /**
     * Envoie [userTurn] avec la consigne [systemPrompt] et renvoie la réponse (sans espaces en bordure). [onLoading] : le modèle
     * doit d'abord être chargé. [isCancelled] est testé une fois le modèle prêt ([RewriteCancelledException]). [label] : nom de
     * l'usage dans le journal (jamais le texte).
     */
    @Suppress("TooGenericExceptionCaught") // relance sur le processeur central quelle que soit l'erreur du GPU (voir abandonGpuAfter)
    suspend fun rewrite(
        model: AiModel,
        systemPrompt: String,
        userTurn: String,
        label: String,
        isCancelled: () -> Boolean = { false },
        onLoading: () -> Unit = {},
    ): String = host.inferenceLock.withLock {
        try {
            rewriteLocked(model, systemPrompt, userTurn, label, isCancelled, onLoading)
        } catch (e: Exception) {
            if (!host.abandonGpuAfter(e)) throw e
            rewriteLocked(model, systemPrompt, userTurn, label, isCancelled, onLoading)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun rewriteLocked(
        model: AiModel,
        systemPrompt: String,
        userTurn: String,
        label: String,
        isCancelled: () -> Boolean,
        onLoading: () -> Unit,
    ): String {
        val engine = host.ensureLoaded(model, onLoading)
        if (isCancelled()) throw RewriteCancelledException()
        // Une seule conversation à la fois sur le moteur : celle du mode Chat est refermée (rouverte au prompt suivant).
        host.releaseOpenConversation()
        val config = ConversationConfig(systemInstruction = Contents.of(systemPrompt))
        val startedAt = SystemClock.elapsedRealtime()
        return withContext(Dispatchers.Default) {
            engine.createConversation(config).use { conversation ->
                nativeCancelRequested = false
                runningConversation = conversation
                try {
                    // Message n'expose pas `text` dans litertlm-android : sa réponse passe par toString() (voir CorrectionEngine).
                    val result = conversation.sendMessage(userTurn).toString().trim()
                    AppLog.i(
                        TAG,
                        "$label: modèle=${model.id} entrée=${userTurn.length} car. sortie=${result.length} car. " +
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
        const val TAG = "RewriteEngine"
        const val NATIVE_GRACE_MS = 500L
    }
}
