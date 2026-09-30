package fr.junade.taipo.clipboard

import fr.junade.taipo.clipboard.ClipboardItems.PinResult
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
 * Teste [PinnedClipRepository] et la règle transactionnelle réelle de [PinnedClipDao.insertBounded]
 * avec un DAO en mémoire (le SQL généré par Room et le chiffrement SQLCipher/Keystore ne sont pas
 * testables en JVM).
 */
class PinnedClipRepositoryTest {

    private class FakeDao : PinnedClipDao() {
        private val state = MutableStateFlow<List<PinnedClipEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<PinnedClipEntity>> = state.map { list ->
            list.sortedWith(compareByDescending<PinnedClipEntity> { it.pinnedAt }.thenByDescending { it.id })
        }

        override suspend fun count(): Int = state.value.size
        override suspend fun existsByText(text: String): Boolean = state.value.any { it.text == text }
        override suspend fun insert(entity: PinnedClipEntity): Long {
            val id = nextId++
            state.value = state.value + entity.copy(id = id)
            return id
        }

        override suspend fun deleteById(id: Long): Int {
            val before = state.value.size
            state.value = state.value.filterNot { it.id == id }
            return before - state.value.size
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = PinnedClipRepository(scope, openDao = { FakeDao() })

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitPinned(predicate: (List<PinnedClip>) -> Boolean): List<PinnedClip> =
        withTimeout(5_000) { repository.pinned.first(predicate) }

    @Test
    fun `pin ajoute un element et pinned le reflete`() = runBlocking {
        assertEquals(PinResult.PINNED, repository.pin("Bonjour", nowMillis = 1))
        assertEquals(listOf("Bonjour"), awaitPinned { it.isNotEmpty() }.map { it.text })
        assertEquals(listOf("Bonjour"), repository.snapshot().map { it.text })
    }

    @Test
    fun `le plus recemment epingle vient en premier`() = runBlocking {
        repository.pin("premier", nowMillis = 1)
        repository.pin("second", nowMillis = 2)
        assertEquals(listOf("second", "premier"), awaitPinned { it.size == 2 }.map { it.text })
    }

    @Test
    fun `epingler deux fois le meme texte est refuse`() = runBlocking {
        assertEquals(PinResult.PINNED, repository.pin("Bonjour", nowMillis = 1))
        assertEquals(PinResult.ALREADY_PINNED, repository.pin("Bonjour", nowMillis = 2))
        assertEquals(1, awaitPinned { it.isNotEmpty() }.size)
    }

    @Test
    fun `le plafond d elements epingles est respecte`() = runBlocking {
        repeat(ClipboardItems.MAX_PINNED) { assertEquals(PinResult.PINNED, repository.pin("élément $it", nowMillis = it.toLong())) }
        assertEquals(PinResult.FULL, repository.pin("un de trop", nowMillis = 999))
        assertEquals(ClipboardItems.MAX_PINNED, awaitPinned { it.size == ClipboardItems.MAX_PINNED }.size)
    }

    @Test
    fun `un texte vide ou trop long est refuse sans toucher la base`() = runBlocking {
        assertEquals(PinResult.EMPTY, repository.pin("  "))
        assertEquals(PinResult.TOO_LONG, repository.pin("a".repeat(ClipboardItems.MAX_PINNED_CHARS + 1)))
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun `remove supprime un element epingle`() = runBlocking {
        repository.pin("a", nowMillis = 1)
        repository.pin("b", nowMillis = 2)
        val pinned = awaitPinned { it.size == 2 }
        assertTrue(repository.remove(pinned.first { it.text == "a" }.id))
        assertEquals(listOf("b"), awaitPinned { it.size == 1 }.map { it.text })
    }

    @Test
    fun `remove d un identifiant inconnu retourne faux`() = runBlocking {
        assertFalse(repository.remove(42))
    }

    @Test
    fun `apres une suppression le meme texte peut etre epingle de nouveau`() = runBlocking {
        repository.pin("a", nowMillis = 1)
        val id = awaitPinned { it.isNotEmpty() }.single().id
        repository.remove(id)
        awaitPinned { it.isEmpty() }
        assertEquals(PinResult.PINNED, repository.pin("a", nowMillis = 2))
    }
}
