package fr.junade.taipo.dictionary

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
 * Point d'accès unique au dictionnaire personnel (story 1.4).
 *
 * - [words] : liste triée des mots, toujours à jour (réémise à chaque
 *   modification en base). [snapshot] en donne la valeur courante sans
 *   suspendre, ce qui convient à l'autocorrection appelée depuis le thread
 *   principal à chaque fin de mot.
 * - [add] / [remove] : écritures asynchrones.
 *
 * Ouverture de la base (Keystore + SQLCipher, potentiellement lente) déclenchée
 * dès la construction, hors thread principal : tant qu'elle n'est pas
 * terminée, [words] est vide (au pire, un mot personnel tapé pendant les
 * premiers instants est corrigé à tort). Le clavier instancie donc le
 * dépôt dès sa création, bien avant la première frappe.
 *
 * Aucune dépendance Android : la base est fournie par [openDao].
 */
class PersonalDictionaryRepository(
    scope: CoroutineScope,
    openDao: () -> PersonalWordDao,
    private val onError: (Throwable) -> Unit = {},
) {

    private val dao: Deferred<PersonalWordDao> = scope.async(Dispatchers.IO) { openDao() }

    private val _words = MutableStateFlow<List<String>>(emptyList())
    val words: StateFlow<List<String>> = _words.asStateFlow()

    init {
        scope.launch {
            try {
                dao.await().observeAll().collect { entities ->
                    _words.value = PersonalDictionary.sortForDisplay(entities.map { it.word })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /** Mots actuels, sans suspendre (liste vide tant que la base n'est pas ouverte). */
    fun snapshot(): List<String> = _words.value

    /** Normalise puis ajoute [raw]. Lève l'exception de la base en cas d'échec d'accès. */
    suspend fun add(raw: String): PersonalDictionary.AddResult {
        val word = PersonalDictionary.normalize(raw) ?: return PersonalDictionary.AddResult.INVALID
        val entity = PersonalWordEntity(normalized = PersonalDictionary.keyOf(word), word = word)
        return dao.await().insertBounded(entity, PersonalDictionary.MAX_WORDS)
    }

    /** Retire [word] (insensible à la casse). Retourne vrai si le mot était présent. */
    suspend fun remove(word: String): Boolean =
        dao.await().deleteByNormalized(PersonalDictionary.keyOf(word)) > 0
}
