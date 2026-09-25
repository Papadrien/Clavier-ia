package fr.papadrien.clavier.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import fr.papadrien.clavier.model.AiModel
import fr.papadrien.clavier.model.ModelFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Charge et garde en mémoire un moteur LiteRT-LM pour le modèle actif, et
 * exécute la correction IA (orthographe/grammaire, épopée 3 du backlog V1).
 *
 * Un seul modèle chargé à la fois : si le modèle demandé change, l'ancien
 * moteur est fermé avant de charger le nouveau (RAM observée ~4 Go en continu
 * pour un modèle chargé, cf. décisions ai-keyboard.md du 23/09/2026).
 *
 * API LiteRT-LM Kotlin vérifiée le 24/09/2026 sur
 * https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
 * (Engine/EngineConfig/ConversationConfig/Conversation.sendMessage). Non testée
 * dans cet environnement (pas d'accès réseau pour compiler) : à vérifier par
 * Adrien lors du premier build.
 */
class CorrectionEngine(private val appContext: Context) {

    private var engine: Engine? = null
    private var loadedModel: AiModel? = null
    private var loadedFile: File? = null

    /**
     * Corrige [text] avec le modèle [model]. [onLoading] est appelé si le
     * modèle doit être (re)chargé avant de pouvoir corriger, pour afficher un
     * indicateur de chargement (décision 8.7).
     */
    suspend fun correct(model: AiModel, text: String, onLoading: () -> Unit): String {
        val activeEngine = ensureEngineLoaded(model, onLoading)
        val conversationConfig = ConversationConfig(
            systemInstruction = Contents.of(CorrectionPrompt.SYSTEM),
        )
        return withContext(Dispatchers.Default) {
            activeEngine.createConversation(conversationConfig).use { conversation ->
                val response = conversation.sendMessage(text)
                response.text?.trim().orEmpty()
            }
        }
    }

    private suspend fun ensureEngineLoaded(model: AiModel, onLoading: () -> Unit): Engine {
        val file = ModelFileResolver.resolve(appContext, model)

        val current = engine
        if (current != null && loadedModel == model && loadedFile?.path == file.path) {
            return current
        }

        onLoading()
        close()

        val newEngine = Engine(
            EngineConfig(
                modelPath = file.path,
                backend = Backend.CPU(),
                cacheDir = appContext.cacheDir.path,
            ),
        )
        withContext(Dispatchers.Default) {
            newEngine.initialize()
        }
        engine = newEngine
        loadedModel = model
        loadedFile = file
        return newEngine
    }

    /** Libère le moteur chargé, s'il y en a un. À appeler quand l'IME est détruit. */
    fun close() {
        engine?.close()
        engine = null
        loadedModel = null
        loadedFile = null
    }
}
