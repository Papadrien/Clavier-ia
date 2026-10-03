package fr.junade.taipo.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Détient l'unique moteur LiteRT-LM du clavier, partagé par la correction ([CorrectionEngine]) et la
 * génération par prompt ([GenerationSession], épopée 5). Un seul modèle est chargé à la fois : si le
 * modèle demandé change, l'ancien moteur est fermé avant de charger le nouveau (RAM observée ~4 Go
 * pour un modèle chargé, cf. décisions ai-keyboard.md du 23/09/2026).
 *
 * Deux garde-fous communs aux deux usages :
 *  - [inferenceLock] : une seule inférence à la fois. Chaque usage le prend avant d'appeler le moteur
 *    (`inferenceLock.withLock { ... }`). Il n'est pas réentrant.
 *  - [releaseOpenConversation] : la conversation de génération reste ouverte entre deux prompts
 *    (décision 14 du contexte épopée 5). Or on suppose qu'un moteur ne porte qu'une conversation à la
 *    fois : la correction la ferme donc avant de créer la sienne, et la génération la rouvre au prompt
 *    suivant en rejouant l'historique (voir [GenerationSession]). La fermeture du moteur la ferme aussi.
 *
 * Code non compilé dans l'environnement de développement (pas de compilateur Kotlin ni de réseau) :
 * à vérifier par Adrien au premier build.
 */
class LlmEngineHost(private val appContext: Context) {

    /** Verrou d'inférence, à tenir pendant toute utilisation du moteur (chargement compris). */
    val inferenceLock = Mutex()

    private var engine: Engine? = null
    private var loadedModel: AiModel? = null
    private var loadedFile: File? = null
    private var conversationReleaser: (() -> Unit)? = null

    /** Vrai pendant le chargement d'un modèle depuis le disque (préchargement ou chargement à la demande). */
    @Volatile var isLoading: Boolean = false
        private set

    private var loadingListener: ((Boolean) -> Unit)? = null

    /**
     * Écoute les changements de [isLoading] (décision 8.7 : indicateur de chargement du mode prompt).
     * Appelé depuis le thread qui charge le modèle : l'écouteur rebascule sur le thread principal.
     */
    fun setLoadingListener(listener: ((Boolean) -> Unit)?) {
        loadingListener = listener
    }

    /** Enregistre la fonction qui ferme la conversation ouverte de la génération (une seule à la fois). */
    fun registerConversationReleaser(releaser: (() -> Unit)?) {
        conversationReleaser = releaser
    }

    /** Ferme la conversation de génération ouverte, s'il y en a une. Sans effet sinon. */
    fun releaseOpenConversation() {
        conversationReleaser?.invoke()
    }

    /**
     * Renvoie le moteur du modèle [model], en le (re)chargeant si besoin. [onLoading] est appelé si le
     * modèle doit être (re)chargé depuis le disque, pour afficher un indicateur (décision 8.7).
     * À appeler avec [inferenceLock] tenu.
     */
    suspend fun ensureLoaded(model: AiModel, onLoading: () -> Unit): Engine {
        val file = ModelFileResolver.resolve(appContext, model)

        val current = engine
        if (current != null && loadedModel == model && loadedFile?.path == file.path) {
            return current
        }

        onLoading()
        close()

        isLoading = true
        loadingListener?.invoke(true)
        val newEngine: Engine
        try {
            newEngine = Engine(
                EngineConfig(
                    modelPath = file.path,
                    backend = Backend.CPU(),
                    cacheDir = appContext.cacheDir.path,
                ),
            )
            withContext(Dispatchers.Default) {
                newEngine.initialize()
            }
        } finally {
            isLoading = false
            loadingListener?.invoke(false)
        }
        engine = newEngine
        loadedModel = model
        loadedFile = file
        return newEngine
    }

    /**
     * Préchargement (épopée 5, décision 18) : charge [model] en arrière-plan, sans l'utiliser, pour que
     * le premier prompt n'attende pas le chargement. Prend [inferenceLock] comme n'importe quel usage :
     * si une correction ou une génération est en cours, il attend son tour ; si le modèle est déjà
     * chargé, il ne fait rien. Lève les exceptions de chargement (modèle absent, échec d'initialisation).
     */
    suspend fun preload(model: AiModel) {
        inferenceLock.withLock { ensureLoaded(model) { } }
    }

    /** Libère le moteur chargé (et la conversation de génération ouverte). À appeler quand l'IME est détruit. */
    fun close() {
        releaseOpenConversation()
        engine?.close()
        engine = null
        loadedModel = null
        loadedFile = null
    }
}
