package fr.junade.taipo.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelFileResolver
import fr.junade.taipo.model.ModelLoadException
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Détient l'unique moteur LiteRT-LM du clavier, partagé par la correction ([CorrectionEngine]) et la
 * génération par prompt ([GenerationSession], épopée 5). Un seul modèle est chargé à la fois : si le
 * modèle demandé change, l'ancien moteur est fermé avant de charger le nouveau (RAM observée ~4 Go
 * pour un modèle chargé, cf. docs/decisions-techniques.md).
 *
 * Deux garde-fous communs aux deux usages :
 *  - [inferenceLock] : une seule inférence à la fois. Chaque usage le prend avant d'appeler le moteur
 *    (`inferenceLock.withLock { ... }`). Il n'est pas réentrant.
 *  - [releaseOpenConversation] : la conversation de génération reste ouverte entre deux prompts
 *    (décision 14 du contexte épopée 5). Or on suppose qu'un moteur ne porte qu'une conversation à la
 *    fois : la correction la ferme donc avant de créer la sienne, et la génération la rouvre au prompt
 *    suivant en rejouant l'historique (voir [GenerationSession]). La fermeture du moteur la ferme aussi.
 *
 * Compilé et validé à l'usage sur appareil (retour d'Adrien, 03/10/2026).
 */
class LlmEngineHost(private val appContext: Context) {

    /** Processeur central ou GPU (au choix de l'utilisateur, voir [AiAcceleration]). */
    private val acceleration = AiAcceleration(appContext).also { it.checkPreviousCrash() }

    /** Verrou d'inférence, à tenir pendant toute utilisation du moteur (chargement compris). */
    val inferenceLock = Mutex()

    // Audit QUAL-04 : lus hors du verrou (isLoaded, releaseIfIdle) depuis plusieurs fils.
    @Volatile private var engine: Engine? = null

    @Volatile private var loadedModel: AiModel? = null

    @Volatile private var loadedFile: File? = null

    /** Le moteur chargé tourne sur GPU (choix de l'utilisateur) : un échec de réponse peut alors venir du GPU. */
    private var loadedOnGpu = false

    /** Le moteur chargé a un backend audio (spike 14.1) : plus lourd en mémoire, mais utilisable aussi pour le texte. */
    private var loadedWithAudio = false
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
     * [withAudio] (spike 14.1, faux par défaut : comportement inchangé) : le moteur doit accepter de l'audio ; un moteur
     * déjà chargé sans audio est alors rechargé avec. Un moteur chargé avec audio reste réutilisé pour le texte.
     * À appeler avec [inferenceLock] tenu.
     */
    suspend fun ensureLoaded(model: AiModel, onLoading: () -> Unit, withAudio: Boolean = false): Engine {
        val file = ModelFileResolver.resolve(appContext, model)

        val current = engine
        if (current != null && loadedModel == model && loadedFile?.path == file.path && (loadedWithAudio || !withAudio)) {
            return current
        }

        onLoading()
        // Audit PERF-01 : la fermeture de l'ancien moteur et la construction du nouveau se font hors du fil principal (appelant).
        withContext(Dispatchers.Default) { closeEngine() }

        isLoading = true
        loadingListener?.invoke(true)
        val newEngine: Engine
        try {
            // GPU si l'utilisateur l'a choisi (texte seulement) : en cas d'échec, repli sur le processeur central.
            newEngine = if (acceleration.useGpu && !withAudio) loadOnGpuOrCpu(file) else loadEngine(file, withAudio, Backend.CPU())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fichier présent mais refusé par le moteur : modèle incompatible ou corrompu (story 8.6).
            throw ModelLoadException("The engine could not load model ${model.id}.", e)
        } finally {
            isLoading = false
            loadingListener?.invoke(false)
        }
        engine = newEngine
        loadedModel = model
        loadedFile = file
        loadedWithAudio = withAudio
        return newEngine
    }

    private fun buildEngine(file: File, withAudio: Boolean, backend: Backend): Engine = Engine(
        if (withAudio) {
            EngineConfig(
                modelPath = file.path,
                backend = backend,
                audioBackend = Backend.CPU(),
                cacheDir = appContext.cacheDir.path,
            )
        } else {
            EngineConfig(
                modelPath = file.path,
                backend = backend,
                cacheDir = appContext.cacheDir.path,
            )
        },
    )

    /** Moteur créé puis initialisé (lecture du modèle) hors du thread principal ; refermé s'il ne s'initialise pas. */
    private suspend fun loadEngine(file: File, withAudio: Boolean, backend: Backend): Engine = withContext(Dispatchers.Default) {
        val engine = buildEngine(file, withAudio, backend)
        try {
            engine.initialize()
        } catch (e: Throwable) {
            runCatching { engine.close() }
            throw e
        }
        engine
    }

    /** Moteur sur GPU ; s'il refuse de s'initialiser, GPU marqué en échec et moteur sur le processeur central. */
    private suspend fun loadOnGpuOrCpu(file: File): Engine {
        acceleration.beginGpuInit()
        val gpu = try {
            loadEngine(file, withAudio = false, backend = Backend.GPU())
        } catch (e: CancellationException) {
            acceleration.endGpuInit(success = true) // interrompu, pas un échec du GPU
            throw e
        } catch (e: Exception) {
            fr.junade.taipo.AppLog.w(TAG, "GPU refusé par le moteur : repli sur le processeur central", e)
            acceleration.endGpuInit(success = false)
            null
        }
        if (gpu != null) {
            acceleration.endGpuInit(success = true)
            loadedOnGpu = true
            return gpu
        }
        return loadEngine(file, withAudio = false, backend = Backend.CPU())
    }

    /**
     * Un échec de réponse ([failure]) alors que le moteur tourne sur GPU : le GPU s'initialise sur certains appareils mais échoue
     * à la première réponse (Pixel 9 : « Can not find OpenCL library on this device », puis `VK_ERROR_DEVICE_LOST`). Le GPU est
     * alors marqué en échec et le moteur fermé (hors du thread principal) : le prochain [ensureLoaded] le recharge sur le
     * processeur central. Vrai si l'appelant peut relancer sa réponse ; faux si le moteur n'est pas sur GPU ou si l'échec ne
     * vient pas de lui (mémoire, texte trop long : même résultat sur le processeur). À appeler avec [inferenceLock] tenu.
     */
    suspend fun abandonGpuAfter(failure: Throwable): Boolean {
        if (!loadedOnGpu || failure is CancellationException) return false
        if (InferenceFailureClassifier.classify(failure) != InferenceFailureCause.UNEXPECTED) return false
        fr.junade.taipo.AppLog.w(TAG, "GPU en échec pendant une réponse : repli sur le processeur central", failure)
        acceleration.markGpuFailed()
        withContext(Dispatchers.Default) { closeEngine() }
        return true
    }

    /** Un moteur est chargé en mémoire. */
    val isLoaded: Boolean get() = engine != null

    /**
     * Revue de code (mémoire) : libère le moteur s'il n'est pas utilisé en ce moment (verrou d'inférence libre). Appelé quand
     * Android manque de mémoire ou quand le clavier reste masqué longtemps ; le modèle se recharge à la prochaine action IA.
     * Renvoie vrai si un moteur a été libéré.
     */
    fun releaseIfIdle(): Boolean {
        if (engine == null || !inferenceLock.tryLock()) return false
        try {
            if (engine == null) return false
            closeEngine()
            return true
        } finally {
            inferenceLock.unlock()
        }
    }

    /**
     * Préchargement (épopée 5, décision 18) : charge [model] en arrière-plan, sans l'utiliser, pour que
     * le premier prompt n'attende pas le chargement. Prend [inferenceLock] comme n'importe quel usage :
     * si une correction ou une génération est en cours, il attend son tour ; si le modèle est déjà
     * chargé, il ne fait rien. Lève les exceptions de chargement (modèle absent, échec d'initialisation).
     */
    suspend fun preload(model: AiModel) {
        inferenceLock.withLock { ensureLoaded(model, onLoading = { }) }
    }

    init {
        synchronized(liveHosts) { liveHosts.add(this) }
    }

    /**
     * Ferme le moteur s'il porte [model], en attendant la fin d'une inférence en cours. À appeler hors du
     * thread principal, avant de supprimer le fichier du modèle (sinon le fichier supprimé resterait
     * utilisé en mémoire).
     */
    fun releaseModel(model: AiModel) {
        runBlocking {
            inferenceLock.withLock {
                if (loadedModel == model) closeEngine()
            }
        }
    }

    /**
     * Libère le moteur chargé (et la conversation de génération ouverte), en attendant la fin d'une inférence en cours (audit
     * BUG-04 : fermer le moteur pendant qu'une réponse tourne le ferait utiliser après sa libération). Hors du fil principal.
     */
    suspend fun closeLocked() {
        inferenceLock.withLock { withContext(Dispatchers.Default) { closeEngine() } }
    }

    /** Ferme le moteur. À n'appeler qu'avec [inferenceLock] tenu (ou depuis [ensureLoaded], qui le tient). */
    private fun closeEngine() {
        releaseOpenConversation()
        engine?.close()
        engine = null
        loadedModel = null
        loadedFile = null
        loadedWithAudio = false
        loadedOnGpu = false
    }

    companion object {
        private const val TAG = "LlmEngineHost"

        /** Ferme tous les moteurs du processus (changement d'accélération dans l'écran « Modèle IA »). Hors du thread principal. */
        fun closeEverywhere() {
            val hosts = synchronized(liveHosts) { liveHosts.toList() }
            hosts.forEach { host -> runBlocking { host.closeLocked() } }
        }

        /** Hôtes vivants du processus (en pratique celui de l'IME) : l'écran de réglages peut ainsi fermer le moteur. */
        private val liveHosts = Collections.newSetFromMap(WeakHashMap<LlmEngineHost, Boolean>())

        /** Ferme, dans tout le processus, les moteurs qui portent [model]. À appeler hors du thread principal. */
        fun releaseModelEverywhere(model: AiModel) {
            val hosts = synchronized(liveHosts) { liveHosts.toList() }
            hosts.forEach { it.releaseModel(model) }
        }
    }
}
