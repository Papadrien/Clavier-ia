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

    private val promptPreferences = CorrectionPromptPreferences(appContext)

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
            systemInstruction = Contents.of(promptPreferences.get()),
        )
        // Les petits modèles (ex. gemma-3-270m-it, "Ultra-léger") respectent mal le
        // rôle système seul et peuvent halluciner (observé : le modèle paraphrase le
        // prompt système au lieu de corriger un mot ambigu comme "touile"). On répète
        // donc une consigne de FORMAT dans le tour utilisateur, en plus du
        // systemInstruction. Important : cette consigne ne redit PAS "orthographe et
        // grammaire uniquement" - cette portée-là ne vit que dans promptPreferences.get()
        // ci-dessus, pour que la personnaliser dans les paramètres ait un effet réel.
        val userTurn = "Réponds uniquement avec le texte corrigé ci-dessous, rien d'autre : " +
            "aucune phrase d'introduction, aucun commentaire, aucun guillemet.\n\nTexte :\n$text"
        return withContext(Dispatchers.Default) {
            activeEngine.createConversation(conversationConfig).use { conversation ->
                val response = conversation.sendMessage(userTurn)
                // `Message.text` n'existe pas encore dans litertlm-android 0.17.1 (build en
                // erreur : "Unresolved reference 'text'"). La doc officielle Kotlin
                // (https://ai.google.dev/edge/litert-lm/android) montre `print(conversation
                // .sendMessage(...))` et `println("Answer: $response")` : Message expose donc
                // sa réponse texte via toString(). À remplacer par `response.text` si une
                // montée de version de litertlm-android l'expose un jour (vérifier
                // l'autocomplétion sur `response.` dans Android Studio).
                response.toString().trim()
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
