package fr.junade.taipo.dictionary.download

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.model.download.DownloadFailure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** État du téléchargement du dictionnaire d'une langue, tel que l'écran des paramètres l'affiche. */
sealed interface DictionaryDownloadState {
    /** Rien en cours (le fichier est installé ou pas encore demandé : l'écran le vérifie sur le disque). */
    data object Idle : DictionaryDownloadState

    /** Lancé, en attente d'une connexion ou du démarrage du travail. */
    data object Queued : DictionaryDownloadState

    data class Downloading(val percent: Int) : DictionaryDownloadState

    data class Failed(val reason: DownloadFailure) : DictionaryDownloadState

    /** Les 3 fichiers viennent d'être installés. */
    data object Installed : DictionaryDownloadState
}

/**
 * États des téléchargements de dictionnaires, partagés dans le processus (le travail, l'écran des paramètres et le
 * clavier y vivent). Ne survit pas à un redémarrage du processus ; les fichiers installés, eux, sont sur le disque.
 */
object DictionaryDownloadTracker {

    fun interface Listener {
        /** Peut être appelé depuis n'importe quel thread. */
        fun onStateChanged(language: KeyboardLanguage, state: DictionaryDownloadState)
    }

    private val states = ConcurrentHashMap<KeyboardLanguage, DictionaryDownloadState>()
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun stateOf(language: KeyboardLanguage): DictionaryDownloadState = states[language] ?: DictionaryDownloadState.Idle

    fun isBusy(language: KeyboardLanguage): Boolean = when (stateOf(language)) {
        DictionaryDownloadState.Queued, is DictionaryDownloadState.Downloading -> true
        else -> false
    }

    fun update(language: KeyboardLanguage, state: DictionaryDownloadState) {
        if (state == DictionaryDownloadState.Idle) states.remove(language) else states[language] = state
        listeners.forEach { it.onStateChanged(language, state) }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}
