package fr.junade.taipo.ai

import android.os.SystemClock
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import fr.junade.taipo.AppLog
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Résultat d'un envoi : le texte reçu ([text], éventuellement partiel) et si la réponse est allée à son terme. */
data class GenerationOutcome(val text: String, val completed: Boolean)

/**
 * Génération par prompt (épopée 5) : conversation continue avec le modèle, guidée par le prompt système
 * [GenerationPrompt.SYSTEM] par défaut, modifiable dans la page « Prompts système » (réponses courtes, texte brut ; il
 * remplace la décision 12.1 depuis le 05/10/2026),
 * réponse reçue en flux, interruptible. Pas d'interface : la vue et l'historique
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
class GenerationSession(
    private val host: LlmEngineHost,
    /** Prompt système, lu à chaque ouverture de conversation : une modification s'applique à la conversation suivante. */
    private val systemPrompt: () -> String = { GenerationPrompt.SYSTEM },
) {

    @Volatile private var conversation: Conversation? = null

    /** Nombre d'échanges (prompt + réponse) que la conversation ouverte a déjà traités. */
    private var exchangesInConversation = 0

    @Volatile private var stopRequested = false
    @Volatile private var generationJob: Job? = null

    /** Portée des arrêts natifs : ils ne doivent bloquer ni le thread principal ni la coroutine d'envoi. */
    private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                active = engine.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(systemPrompt())),
                )
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
                                if (stopRequested) return@collect // morceau arrivé après le stop : ignoré
                                val chunk = chunkText(message.toString())
                                if (chunk.isNotEmpty()) {
                                    received.append(chunk)
                                    chunks++
                                    onChunk(chunk)
                                }
                            }
                        }
                        generationJob = job
                        if (stopRequested) requestNativeStop(active, job) // stop arrivé entre le test précédent et le lancement
                        job.join()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine appelante annulée (fermeture du clavier) : le flux Kotlin est coupé, mais la
                // génération native tourne encore. On l'arrête puis on lui laisse le temps de finir.
                stopNative(active)
                closeConversationOffMain(waitForNative = true)
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
                // Hors du thread principal : close() peut attendre l'arrêt de la génération native, ce qui
                // gelait le clavier après un stop.
                closeConversationOffMain(waitForNative = stopRequested)
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
        val job = generationJob ?: return
        val running = conversation ?: return
        requestNativeStop(running, job)
    }

    /**
     * Ferme la conversation et oublie son état (fermeture du clavier, décision 14). Si une génération est
     * en cours, c'est [send] qui referme la conversation une fois la génération native réellement arrêtée :
     * la fermer ici, tout de suite, ferait planter le moteur (SIGSEGV dans `callback_thread`).
     */
    fun reset() {
        stop()
        if (generationJob == null) closeConversation()
    }

    /**
     * Arrête la génération native de [target] (`cancelProcess`), sans toucher au flux Kotlin : [send] se
     * termine quand le moteur a lui-même fini d'appeler ses rappels. Si le moteur ne rend pas la main dans
     * [NATIVE_STOP_TIMEOUT_MS], le flux est coupé de force pour ne pas garder le verrou d'inférence.
     */
    private fun requestNativeStop(target: Conversation, job: Job) {
        stopScope.launch {
            stopNative(target)
            delay(NATIVE_STOP_TIMEOUT_MS)
            if (job.isActive) {
                AppLog.w(TAG, "le moteur n'a pas rendu la main après le stop : flux coupé de force")
                job.cancel()
            }
        }
    }

    private fun stopNative(target: Conversation) {
        try {
            target.cancelProcess()
        } catch (e: Exception) {
            AppLog.w(TAG, "arrêt de la génération native", e)
        }
    }

    /**
     * Comme [closeConversation], mais sur un thread d'arrière-plan et sans être annulable : [send] reprend
     * sur le thread principal après la génération, et `Conversation.close()` peut bloquer tant que le
     * moteur natif n'a pas fini de s'arrêter.
     */
    private suspend fun closeConversationOffMain(waitForNative: Boolean = false) {
        withContext(NonCancellable + Dispatchers.Default) {
            // Après un stop, le fil natif des rappels peut encore remonter sa pile quelques instants : fermer
            // la conversation à ce moment-là provoquait le SIGSEGV observé le 06/10/2026.
            if (waitForNative) delay(NATIVE_GRACE_MS)
            closeConversation()
        }
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

        /** Délai laissé au moteur pour finir sa génération après `cancelProcess`, avant de couper le flux de force. */
        private const val NATIVE_STOP_TIMEOUT_MS = 5_000L

        /** Pause entre la fin du flux (après un stop) et la fermeture de la conversation native. */
        private const val NATIVE_GRACE_MS = 500L
    }
}
