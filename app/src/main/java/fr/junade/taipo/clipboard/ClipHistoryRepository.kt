package fr.junade.taipo.clipboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Point d'accès unique à l'historique du presse-papiers (story 2.9), sur le modèle de
 * [PinnedClipRepository].
 *
 * - [history] : lignes à jour (de la plus récente à la plus ancienne), réémises à chaque
 *   modification en base ; [snapshot] en donne la valeur courante sans suspendre. Elle peut
 *   contenir des lignes expirées pas encore purgées : le filtre par âge se fait à la construction
 *   des cartes ([ClipboardItems.build]).
 * - [record] / [updateText] / [replaceText] / [remove] / [purgeExpired] : écritures asynchrones.
 *
 * Rétention de [retentionMillis] (1 h) à partir de la copie, [maxEntries] lignes au plus. La
 * purge physique des lignes expirées a lieu à l'ouverture de la base, à chaque [record] et à la
 * demande ([purgeExpired], à l'ouverture du panneau). L'horloge est injectable pour les tests.
 * Aucune dépendance Android : la base est fournie par [openDao].
 */
class ClipHistoryRepository(
    scope: CoroutineScope,
    openDao: () -> ClipHistoryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val retentionMillis: Long = ClipboardItems.HISTORY_RETENTION_MILLIS,
    private val maxEntries: Int = ClipboardItems.MAX_HISTORY,
    private val onError: (Throwable) -> Unit = {},
) {

    private val dao: Deferred<ClipHistoryDao> = scope.async(Dispatchers.IO) { openDao() }

    private val _history = MutableStateFlow<List<ClipHistoryEntry>>(emptyList())
    val history: StateFlow<List<ClipHistoryEntry>> = _history.asStateFlow()

    init {
        scope.launch {
            try {
                val opened = dao.await()
                try {
                    opened.deleteOlderThan(clock() - retentionMillis) // purge au démarrage de la base
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    onError(t)
                }
                opened.observeAll().collect { entities ->
                    _history.value = entities.map { ClipHistoryEntry(it.id, it.text, it.copiedAt, it.sensitive) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /** Historique actuel, sans suspendre (vide tant que la base n'est pas ouverte). */
    fun snapshot(): List<ClipHistoryEntry> = _history.value

    /**
     * Enregistre une nouvelle copie. [copiedAtMillis] : heure de la copie donnée par le système
     * (0 ou moins = inconnue : la copie est datée de maintenant, et si son texte est déjà dans
     * l'historique, la ligne garde son heure d'origine). Refusés sans toucher la base : texte vide
     * ou plus long que [ClipboardItems.MAX_HISTORY_CHARS] (la copie reste alors en mémoire du clavier
     * seulement). Le caractère sensible est enregistré comme drapeau, pas écarté. Retourne vrai si
     * la copie est dans l'historique. Lève l'exception de la base en cas d'échec d'accès.
     */
    suspend fun record(text: String, copiedAtMillis: Long, sensitive: Boolean): Boolean {
        if (ClipboardItems.historyRefusal(text)) return false
        val now = clock()
        val known = copiedAtMillis > 0
        val copiedAt = if (known) minOf(copiedAtMillis, now) else now
        return dao.await().record(text, copiedAt, known, sensitive, now, retentionMillis, maxEntries)
    }

    /** Supprime les copies expirées (à l'ouverture du panneau). */
    suspend fun purgeExpired() {
        dao.await().deleteOlderThan(clock() - retentionMillis)
    }

    /**
     * Modifie le texte de la ligne [id] (texte vide ou trop long refusés). La date de copie est
     * conservée, donc l'expiration aussi ; un texte déjà présent dans une autre ligne la fusionne.
     */
    suspend fun updateText(id: Long, text: String): ClipboardItems.EditResult {
        ClipboardItems.editRefusal(text)?.let { return it }
        return if (dao.await().updateText(id, text) > 0) {
            ClipboardItems.EditResult.SAVED
        } else {
            ClipboardItems.EditResult.NOT_FOUND
        }
    }

    /**
     * Modifie la dernière copie (qui vit aussi en mémoire du clavier) : remplace [oldText] par
     * [newText] dans sa ligne, si elle y est. Sans cela, l'ancien texte réapparaîtrait en doublon
     * après un redémarrage du clavier. Retourne vrai si une ligne a été modifiée.
     */
    suspend fun replaceText(oldText: String, newText: String): Boolean {
        if (ClipboardItems.editRefusal(newText) != null) return false
        val opened = dao.await()
        val id = opened.findIdByText(oldText) ?: return false
        return opened.updateText(id, newText) > 0
    }

    /** Supprime la ligne [id]. Retourne vrai si elle existait. */
    suspend fun remove(id: Long): Boolean = dao.await().deleteById(id) > 0
}
