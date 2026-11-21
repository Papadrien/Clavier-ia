package fr.junade.taipo

import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Préchargement du modèle IA (optimisation du 21/11/2026) : le chargement depuis le disque prend plusieurs secondes (Gemma 4 sur
 * processeur), et c'était lui que l'on attendait au premier appui sur Corriger, Ton ou Traduire. Le modèle se charge donc en
 * arrière-plan avant d'être demandé :
 *
 * - à l'ouverture du panneau IA (l'utilisateur va choisir une action) ;
 * - quand le champ reçoit du texte, si une action IA a servi récemment ([RECENT_USE_MS]) : l'utilisateur s'en sert, il en aura
 *   sans doute encore besoin. Sans usage récent, rien n'est chargé (mémoire et batterie épargnées).
 *
 * Le moteur reste libéré comme avant quand le clavier reste masqué ou qu'Android manque de mémoire (`ImeMemoryManager`).
 */
internal class AiWarmup(
    private val scope: CoroutineScope,
    private val llmHost: () -> LlmEngineHost,
    private val activeModel: () -> AiModel?,
) {

    private var job: Job? = null

    /** Ouverture du panneau IA : préchargement si besoin. */
    fun onAiPanelOpened() = preload(requireRecentUse = false)

    /** Le champ a du texte (barre de saisie) : préchargement si l'IA a servi récemment. */
    fun onFieldHasText() = preload(requireRecentUse = true)

    private fun preload(requireRecentUse: Boolean) {
        if (job?.isActive == true) return
        val model = activeModel() ?: return
        val host = llmHost()
        if (!shouldPreload(System.currentTimeMillis(), host.lastUseAt, host.isLoaded, host.isLoading, requireRecentUse)) return
        job = scope.launch {
            try {
                // Moteur avec audio si la dernière action en avait besoin (il sert aussi au texte) : pas de rechargement ensuite.
                host.preload(model, withAudio = host.lastUseWithAudio && model.hasAudioInput)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                // Modèle absent ou refusé : l'action, elle, affichera l'erreur. Ici, on journalise seulement.
                AppLog.w(TAG, "préchargement du modèle", t)
            }
        }
    }

    companion object {
        private const val TAG = "AiWarmup"

        /** Une action IA dans les dernières 24 h vaut usage récent. */
        const val RECENT_USE_MS = 24 * 60 * 60 * 1000L

        /** Logique pure, testée en JVM. */
        fun shouldPreload(nowMs: Long, lastUseMs: Long, loaded: Boolean, loading: Boolean, requireRecentUse: Boolean): Boolean {
            if (loaded || loading) return false
            if (!requireRecentUse) return true
            return lastUseMs > 0 && nowMs - lastUseMs in 0..RECENT_USE_MS
        }
    }
}
