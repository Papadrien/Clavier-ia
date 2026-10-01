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
 * Point d'accès unique aux éléments épinglés (stories 2.5 à 2.8).
 *
 * - [pinned] : liste à jour (du plus récemment épinglé au plus ancien), réémise à chaque
 *   modification en base ; [snapshot] en donne la valeur courante sans suspendre.
 * - [pin] / [updateText] / [setLabel] / [clearLabel] / [remove] : écritures asynchrones.
 *
 * L'ouverture de la base (Keystore + SQLCipher, potentiellement lente) est déclenchée dès la
 * construction, hors thread principal : tant qu'elle n'est pas terminée, [pinned] est vide.
 * Aucune dépendance Android : la base est fournie par [openDao].
 */
class PinnedClipRepository(
    scope: CoroutineScope,
    openDao: () -> PinnedClipDao,
    private val onError: (Throwable) -> Unit = {},
) {

    private val dao: Deferred<PinnedClipDao> = scope.async(Dispatchers.IO) { openDao() }

    private val _pinned = MutableStateFlow<List<PinnedClip>>(emptyList())
    val pinned: StateFlow<List<PinnedClip>> = _pinned.asStateFlow()

    init {
        scope.launch {
            try {
                dao.await().observeAll().collect { entities ->
                    _pinned.value = entities.map { PinnedClip(it.id, it.text, it.pinnedAt, it.label) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /** Éléments épinglés actuels, sans suspendre (vide tant que la base n'est pas ouverte). */
    fun snapshot(): List<PinnedClip> = _pinned.value

    /**
     * Épingle [text] (texte vide ou trop long refusés, doublon et plafond détectés dans la base).
     * Le caractère sensible d'un contenu est vérifié par l'appelant (voir [ClipboardItems.pinRefusal]).
     * Lève l'exception de la base en cas d'échec d'accès.
     */
    suspend fun pin(text: String, nowMillis: Long = System.currentTimeMillis()): ClipboardItems.PinResult {
        ClipboardItems.pinRefusal(text, sensitive = false)?.let { return it }
        return dao.await().insertBounded(PinnedClipEntity(text = text, pinnedAt = nowMillis), ClipboardItems.MAX_PINNED)
    }

    /**
     * Story 2.7 : donne l'étiquette [raw] à l'élément [id] (espaces autour retirés ; vide ou plus de
     * [ClipboardItems.MAX_LABEL_CHARS] caractères refusés). Remplace l'étiquette existante (story 2.8).
     */
    suspend fun setLabel(id: Long, raw: String): ClipboardItems.LabelResult {
        ClipboardItems.labelRefusal(raw)?.let { return it }
        val label = ClipboardItems.normalizeLabel(raw)
        return if (dao.await().updateLabel(id, label) > 0) {
            ClipboardItems.LabelResult.SAVED
        } else {
            ClipboardItems.LabelResult.NOT_FOUND
        }
    }

    /** Story 2.8 : retire l'étiquette de l'élément [id]. Retourne vrai s'il existait. */
    suspend fun clearLabel(id: Long): Boolean = dao.await().updateLabel(id, null) > 0

    /** Supprime l'élément [id]. Retourne vrai s'il existait. */
    suspend fun remove(id: Long): Boolean = dao.await().deleteById(id) > 0

    /**
     * Story 2.6 : remplace le texte de l'élément [id] (texte vide ou trop long refusés). L'ordre et
     * l'étiquette sont conservés ; un texte identique à celui d'un autre élément est accepté.
     */
    suspend fun updateText(id: Long, text: String): ClipboardItems.EditResult {
        ClipboardItems.editRefusal(text)?.let { return it }
        return if (dao.await().updateText(id, text) > 0) {
            ClipboardItems.EditResult.SAVED
        } else {
            ClipboardItems.EditResult.NOT_FOUND
        }
    }
}
