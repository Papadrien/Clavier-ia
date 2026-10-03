package fr.junade.taipo.ai

import android.os.SystemClock
import com.google.ai.edge.litertlm.Conversation
import fr.junade.taipo.AppLog
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Résultat d'un envoi : le texte reçu ([text], éventuellement partiel) et si la réponse est allée à son terme. */
data class GenerationOutcome(val text: String, val completed: Boolean)

/**
 * Génération par prompt (épopée 5) : conversation continue avec le modèle, sans prompt système
 * (décision 12.1), réponse reçue en flux, interruptible. Pas d'interface : la vue et l'historique
 * affiché sont des phases ultérieures (voir le découpage de la 5.1 dans le contexte épopée 5).
 *
 * Fonctionnement :
 *  - la conversation LiteRT-LM reste ouverte entre deux prompts (décision 14) pour éviter de
 *    retraiter l'historique à chaque fois ;
 *  - l'appelant fournit à chaque envoi l'historique complet ([history], sans le prompt en cours) : il
 *    en reste propriétaire (décisions 9 et 11, partiel après stop compris). La session ne s'y fie que
 *    pour reconstruire une conversation ;
 *  - la conversation est rouverte, historique rejoué via [ChatTranscript], dès qu'elle est fermée
 *    (stop, erreur, correction passée entre-temps, changement de modèle) ou que [history] n'a pas la
 *    taille de ce qu'elle a déjà vu. On ne suppose donc pas que LiteRT-LM enregistre un tour interrompu
 *    (risque noté au point 9 du contexte) : après un stop, la conversation est toujours refermée ;
 *  - [reset] ferme tout (à appeler à la fermeture du clavier, décision 14) ; si l'historique de
 *    l'appelant est vidé sans [reset], la prochaine conversation reste cohérente grâce au contrôle de taille.
 *
 * Threads : [onChunk] est appelé sur un thread d'arrière-plan ; l'appelant bascule sur le thread
 * principal pour mettre à jour la vue.
 *
 * API LiteRT-LM utilisée en plus de celle de la correction : `Conversation.sendMessageAsync(String)`,
 * renvoyant un `Flow<Message>` de morceaux de réponse successifs (chaque `Message` lu via
 * `toString()`, comme pour `sendMessage`). Validé à l'usage sur appareil (retour d'Adrien,
 * 03/10/2026, litertlm-android 0.17.1) ; voir [chunkText] si l'API venait à émettre des cumuls.
 */
class GenerationSession(private val host: LlmEngineHost) {

    private var conversation: Conversation? = null

    /** Nombre d'échanges (prompt + réponse) que la conversation ouverte a déjà traités. */
    private var exchangesInConversation = 0

    @Volatile private var stopRequested = false
    @Volatile private var generationJob: Job? = null

    init {
        // La correction ou la fermeture du moteur ferment la conversation : on l'oublie ici.
        host.registerConversationReleaser { closeConversation() }
    }

    /**
     * Envoie [prompt] (en tenant compte de [history], échanges précédents dans l'ordre) et reçoit la
     * réponse en flux via [onChunk]. Ne renvoie qu'à la fin de la génération ou après [stop].
     * [onLoading] : le modèle doit être rechargé depuis le disque (décision 8.7).
     *
     * Lève les exceptions du moteur (modèle absent, échec d'inférence) : l'appelant affiche la cause
     * (point 5 du contexte) et garde le partiel qu'il a déjà reçu via [onChunk]. Une annulation de la
     * coroutine appelante est propagée.
     */
    suspend fun send(
        model: AiModel,
        history: List<ChatExchange>,
        prompt: String,
        onLoading: () -> Unit,
        onChunk: (String) -> Unit,
    ): GenerationOutcome {
        stopRequested = false
        return host.inferenceLock.withLock {
            val engine = host.ensureLoaded(model, onLoading)
            if (stopRequested) return@withLock GenerationOutcome("", completed = false)

            val reusable = conversation != null && exchangesInConversation == history.size
            val userTurn: String
            val active: Conversation
            if (reusable) {
                active = conversation!!
                userTurn = prompt
            } else {
                closeConversation()
                active = engine.createConversation()
                conversation = active
                userTurn = ChatTranscript.build(history, prompt)
            }

            val received = StringBuilder()
            var chunks = 0
            var failure: Throwable? = null
            val startedAt = SystemClock.elapsedRealtime()
            try {
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        val job = launch {
                            active.sendMessageAsync(userTurn).collect { message ->
                                val chunk = chunkText(message.toString())
                                if (chunk.isNotEmpty()) {
                                    received.append(chunk)
                                    chunks++
                                    onChunk(chunk)
                                }
                            }
                        }
                        generationJob = job
                        if (stopRequested) job.cancel() // stop arrivé entre le test précédent et le lancement
                        job.join()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                closeConversation()
                throw e
            } catch (e: Exception) {
                failure = e
            } finally {
                generationJob = null
            }

            val completed = failure == null && !stopRequested
            if (completed) {
                exchangesInConversation = history.size + 1
            } else {
                // Tour interrompu ou en échec : on ne sait pas ce que le moteur en a retenu, donc on
                // referme. Le prompt suivant rouvrira avec l'historique de l'appelant.
                closeConversation()
            }
            // Diagnostic : jamais le texte lui-même, seulement les longueurs et la durée.
            AppLog.i(
                TAG,
                "génération: modèle=${model.id} historique=${history.size} échange(s) prompt=${prompt.length} car. " +
                    "reçu=${received.length} car. morceaux=$chunks terminée=$completed " +
                    "rejouée=${!reusable} durée=${SystemClock.elapsedRealtime() - startedAt} ms",
            )
            // Un stop demandé n'est pas une erreur ; toute autre erreur remonte à l'appelant.
            if (failure != null && !stopRequested) throw failure
            GenerationOutcome(received.toString().trim(), completed)
        }
    }

    /**
     * Interrompt la génération en cours (bouton stop, décision 10). [send] renvoie alors le texte déjà
     * reçu avec `completed = false`. Sans effet si rien n'est en cours. Peut être appelé depuis le
     * thread principal.
     */
    fun stop() {
        stopRequested = true
        generationJob?.cancel()
    }

    /** Ferme la conversation et oublie son état (fermeture du clavier, décision 14). */
    fun reset() {
        stop()
        closeConversation()
    }

    private fun closeConversation() {
        val old = conversation
        conversation = null
        exchangesInConversation = 0
        if (old != null) {
            try {
                old.close()
            } catch (e: Exception) {
                // Moteur déjà fermé ou génération native encore en cours d'arrêt : rien à faire de plus.
                AppLog.w(TAG, "fermeture de la conversation de génération", e)
            }
        }
    }

    /**
     * Texte à ajouter à la réponse pour un message reçu du flux. Les morceaux sont des deltas
     * (comportement validé sur appareil le 03/10/2026). Si une future version de LiteRT-LM émettait des
     * morceaux cumulatifs (chaque message contenant toute la réponse jusque-là), c'est ici qu'il
     * faudrait ne garder que la partie nouvelle.
     */
    private fun chunkText(message: String): String = message

    private companion object {
        private const val TAG = "GenerationSession"
    }
}
