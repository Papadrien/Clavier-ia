package fr.junade.taipo.ai

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import fr.junade.taipo.AppLog

/**
 * Exécute la correction IA (orthographe/grammaire, épopée 3 du backlog V1) avec le moteur LiteRT-LM
 * partagé [host] (voir [LlmEngineHost] : un seul modèle chargé à la fois, partagé avec la génération
 * par prompt).
 *
 * API LiteRT-LM Kotlin vérifiée le 24/09/2026 sur
 * https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
 * (Engine/EngineConfig/ConversationConfig/Conversation.sendMessage). Validée à l'usage sur
 * appareil (retour d'Adrien, 03/10/2026).
 */
class CorrectionEngine(
    appContext: Context,
    private val host: LlmEngineHost = LlmEngineHost(appContext),
) {

    private val promptPreferences = CorrectionPromptPreferences(appContext)

    /**
     * Corrige [text] avec le modèle [model]. [onLoading] est appelé si le
     * modèle doit être (re)chargé avant de pouvoir corriger, pour afficher un
     * indicateur de chargement (décision 8.7). [protectedWords] : mots du dictionnaire personnel
     * présents dans [text] (voir [ProtectedWords.inText]), à ne pas corriger.
     */
    suspend fun correct(
        model: AiModel,
        text: String,
        protectedWords: List<String> = emptyList(),
        onLoading: () -> Unit,
    ): String = host.inferenceLock.withLock {
        val activeEngine = host.ensureLoaded(model, onLoading)
        // Le moteur ne porte qu'une conversation à la fois : on ferme celle de la génération par
        // prompt (elle sera rouverte, historique rejoué, au prompt suivant).
        host.releaseOpenConversation()
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(promptPreferences.get()),
        )
        // Les petits modèles (ex. gemma-3-270m-it, "Ultra-léger") respectent mal le rôle système seul
        // et peuvent halluciner : la consigne de FORMAT est donc répétée dans le tour utilisateur,
        // avec les mots du dictionnaire personnel présents dans le texte (voir CorrectionPrompt.userTurn).
        val userTurn = CorrectionPrompt.userTurn(text, protectedWords)
        val startedAt = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Default) {
            activeEngine.createConversation(conversationConfig).use { conversation ->
                val response = conversation.sendMessage(userTurn)
                // `Message.text` n'existe pas encore dans litertlm-android 0.17.1 (build en
                // erreur : "Unresolved reference 'text'"). La doc officielle Kotlin
                // (https://ai.google.dev/edge/litert-lm/android) montre `print(conversation
                // .sendMessage(...))` et `println("Answer: $response")` : Message expose donc
                // sa réponse texte via toString(). À remplacer par `response.text` si une
                // montée de version de litertlm-android l'expose un jour (vérifier
                // l'autocomplétion sur `response.` dans Android Studio).
                val result = response.toString().trim()
                // Diagnostic : jamais le texte lui-même, seulement les longueurs et la durée.
                AppLog.i(
                    TAG,
                    "correction: modèle=${model.id} entrée=${text.length} car. sortie=${result.length} car. " +
                        "identique=${result == text.trim()} durée=${SystemClock.elapsedRealtime() - startedAt} ms",
                )
                result
            }
        }
    }

    private companion object {
        private const val TAG = "CorrectionEngine"
    }

    /** Libère le moteur partagé s'il y en a un de chargé. À appeler quand l'IME est détruit. */
    fun close() {
        host.close()
    }
}
