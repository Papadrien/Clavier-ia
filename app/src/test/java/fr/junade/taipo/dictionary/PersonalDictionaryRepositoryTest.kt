package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.PersonalDictionary.AddResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Teste [PersonalDictionaryRepository] et la règle transactionnelle réelle de
 * [PersonalWordDao.insertBounded] avec un DAO en mémoire (le SQL généré par
 * Room et le chiffrement SQLCipher/Keystore ne sont pas testables en JVM).
 */
class PersonalDictionaryRepositoryTest {

    private class FakeDao : PersonalWordDao() {
        private val state = MutableStateFlow<Map<String, PersonalWordEntity>>(emptyMap())

        override fun observeAll(): Flow<List<PersonalWordEntity>> = state.map { it.values.toList() }
        override suspend fun count(): Int = state.value.size
        override suspend fun exists(normalized: String): Boolean = normalized in state.value
        override suspend fun insert(entity: PersonalWordEntity) {
            state.value = state.value + (entity.normalized to entity)
        }

        override suspend fun deleteByNormalized(normalized: String): Int {
            val present = normalized in state.value
            state.value = state.value - normalized
            return if (present) 1 else 0
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = PersonalDictionaryRepository(scope, openDao = { FakeDao() })

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitWords(predicate: (List<String>) -> Boolean): List<String> =
        withTimeout(5_000) { repository.words.first(predicate) }

    @Test
    fun `add ajoute un mot et words le reflete`() = runBlocking {
        assertEquals(AddResult.ADDED, repository.add("Adrien"))
        assertEquals(listOf("Adrien"), awaitWords { it.isNotEmpty() })
        assertEquals(listOf("Adrien"), repository.snapshot())
    }

    @Test
    fun `add refuse un doublon meme avec une casse differente`() = runBlocking {
        assertEquals(AddResult.ADDED, repository.add("Taipo"))
        assertEquals(AddResult.ALREADY_PRESENT, repository.add("taipo"))
        assertEquals(listOf("Taipo"), awaitWords { it.isNotEmpty() })
    }

    @Test
    fun `add refuse les mots invalides`() = runBlocking {
        assertEquals(AddResult.INVALID, repository.add(""))
        assertEquals(AddResult.INVALID, repository.add("deux mots"))
        assertEquals(AddResult.INVALID, repository.add("abc123"))
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun `add conserve la casse saisie`() = runBlocking {
        repository.add("iPhone")
        assertEquals(listOf("iPhone"), awaitWords { it.isNotEmpty() })
    }

    @Test
    fun `words est trie sans tenir compte de la casse`() = runBlocking {
        repository.add("zoe")
        repository.add("Adrien")
        repository.add("bob")
        assertEquals(listOf("Adrien", "bob", "zoe"), awaitWords { it.size == 3 })
    }

    @Test
    fun `remove retire un mot insensible a la casse`() = runBlocking {
        repository.add("Taipo")
        awaitWords { it.isNotEmpty() }
        assertTrue(repository.remove("TAIPO"))
        awaitWords { it.isEmpty() }
        assertFalse(repository.remove("Taipo"))
    }

    @Test
    fun `add refuse au dela du plafond mais accepte encore un doublon en erreur ALREADY_PRESENT`() = runBlocking {
        repeat(PersonalDictionary.MAX_WORDS) { index ->
            assertEquals(AddResult.ADDED, repository.add("mot" + letters(index)))
        }
        assertEquals(AddResult.FULL, repository.add("nouveau"))
        assertEquals(AddResult.ALREADY_PRESENT, repository.add("MOTA"))
    }

    /** Suffixe alphabétique unique (base 26) pour générer des mots distincts sans chiffres. */
    private fun letters(n: Int): String {
        var value = n
        val sb = StringBuilder()
        do {
            sb.append('a' + value % 26)
            value /= 26
        } while (value > 0)
        return sb.toString()
    }
}
