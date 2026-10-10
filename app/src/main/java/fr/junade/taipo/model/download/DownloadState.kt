package fr.junade.taipo.model.download

import fr.junade.taipo.model.AiModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Cause d'un échec de téléchargement, traduite en phrase par l'écran. */
enum class DownloadFailure {
    /** Connexion perdue, ou passage sur données mobiles sans accord. */
    NETWORK,

    /** Le serveur a répondu autre chose qu'un fichier complet (code HTTP, taille hors plage, non HTTPS). */
    SERVER,

    /** Pas assez de place (contrôle refait avec la vraie taille annoncée). */
    NO_SPACE,

    /** Fichier incomplet ou empreinte différente de la référence. */
    CORRUPTED,

    /** Autre erreur d'entrée-sortie. */
    OTHER,
}

/** État d'un téléchargement de modèle, tel que l'écran l'affiche. */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** [totalBytes] <= 0 tant que le serveur n'a pas annoncé la taille. */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadState {
        /** Pourcentage 0..100, ou null si la taille est inconnue. */
        val percent: Int?
            get() = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).coerceIn(0, 100).toInt() else null
    }

    data object Verifying : DownloadState

    data class Failed(val reason: DownloadFailure) : DownloadState
}

/**
 * État des téléchargements en cours, partagé dans le processus (le worker et les écrans y vivent). Ne survit pas
 * à un redémarrage du processus : un téléchargement interrompu repart de zéro (reprise reportée en V2).
 */
object DownloadTracker {

    fun interface Listener {
        fun onStateChanged(model: AiModel, state: DownloadState)
    }

    private val states = ConcurrentHashMap<AiModel, DownloadState>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun stateOf(model: AiModel): DownloadState = states[model] ?: DownloadState.Idle

    fun isBusy(model: AiModel): Boolean = when (stateOf(model)) {
        is DownloadState.Downloading, DownloadState.Verifying -> true
        else -> false
    }

    /** Peut être appelé depuis n'importe quel thread : les écouteurs rebasculent sur le thread principal. */
    fun update(model: AiModel, state: DownloadState) {
        if (state == DownloadState.Idle) states.remove(model) else states[model] = state
        listeners.forEach { it.onStateChanged(model, state) }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // --- Modèle vocal (story 8.15) : un seul téléchargement pour ses 4 fichiers, donc un seul état. -----------------

    fun interface VoiceListener {
        fun onVoiceStateChanged(state: DownloadState)
    }

    @Volatile
    private var voiceState: DownloadState = DownloadState.Idle
    private val voiceListeners = CopyOnWriteArraySet<VoiceListener>()

    fun voiceStateOf(): DownloadState = voiceState

    fun isVoiceBusy(): Boolean = when (voiceState) {
        is DownloadState.Downloading, DownloadState.Verifying -> true
        else -> false
    }

    /** Peut être appelé depuis n'importe quel thread : les écouteurs rebasculent sur le thread principal. */
    fun updateVoice(state: DownloadState) {
        voiceState = state
        voiceListeners.forEach { it.onVoiceStateChanged(state) }
    }

    fun addVoiceListener(listener: VoiceListener) {
        voiceListeners.add(listener)
    }

    fun removeVoiceListener(listener: VoiceListener) {
        voiceListeners.remove(listener)
    }
}
