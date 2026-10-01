package fr.junade.taipo.clipboard

import fr.junade.taipo.clipboard.ClipboardItems.EditResult
import fr.junade.taipo.clipboard.ClipboardItems.LabelResult
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

        override suspend fun updateText(id: Long, text: String): Int {
            val before = state.value
            if (before.none { it.id == id }) return 0
            state.value = before.map { if (it.id == id) it.copy(text = text) else it }
            return 1
        }

        override suspend fun updateLabel(id: Long, label: String?): Int {
            val before = state.value
            if (before.none { it.id == id }) return 0
            state.value = before.map { if (it.id == id) it.copy(label = label) else it }
            return 1
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

    // Story 2.6

    @Test
    fun `updateText remplace le texte en gardant l ordre et l etiquette`() = runBlocking {
        repository.pin("a", nowMillis = 1)
        repository.pin("b", nowMillis = 2)
        val before = awaitPinned { it.size == 2 }
        assertEquals(EditResult.SAVED, repository.updateText(before.first { it.text == "a" }.id, "a modifié"))
        val after = awaitPinned { list -> list.any { it.text == "a modifié" } }
        assertEquals(listOf("b", "a modifié"), after.map { it.text })
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.map { it.pinnedAtMillis }, after.map { it.pinnedAtMillis })
    }

    @Test
    fun `un texte modifie identique a un autre element epingle est accepte`() = runBlocking {
        repository.pin("a", nowMillis = 1)
        repository.pin("b", nowMillis = 2)
        val pinned = awaitPinned { it.size == 2 }
        assertEquals(EditResult.SAVED, repository.updateText(pinned.first { it.text == "a" }.id, "b"))
        assertEquals(listOf("b", "b"), awaitPinned { list -> list.all { it.text == "b" } }.map { it.text })
    }

    @Test
    fun `updateText refuse un texte vide ou trop long sans toucher la base`() = runBlocking {
        repository.pin("a", nowMillis = 1)
        val id = awaitPinned { it.isNotEmpty() }.single().id
        assertEquals(EditResult.EMPTY, repository.updateText(id, "   "))
        assertEquals(EditResult.TOO_LONG, repository.updateText(id, "x".repeat(ClipboardItems.MAX_PINNED_CHARS + 1)))
        assertEquals(EditResult.SAVED, repository.updateText(id, "x".repeat(ClipboardItems.MAX_PINNED_CHARS)))
        assertEquals(ClipboardItems.MAX_PINNED_CHARS, awaitPinned { it.single().text.startsWith("x") }.single().text.length)
    }

    @Test
    fun `updateText d un identifiant inconnu retourne NOT_FOUND`() = runBlocking {
        assertEquals(EditResult.NOT_FOUND, repository.updateText(42, "texte"))
    }

    // Stories 2.7 et 2.8

    private suspend fun pinnedId(text: String): Long {
        repository.pin(text, nowMillis = System.nanoTime())
        return awaitPinned { list -> list.any { it.text == text } }.first { it.text == text }.id
    }

    @Test
    fun `un element epingle n a pas d etiquette au depart`() = runBlocking {
        pinnedId("a")
        assertEquals(listOf<String?>(null), awaitPinned { it.isNotEmpty() }.map { it.label })
    }

    @Test
    fun `setLabel enregistre l etiquette sans espaces autour et en gardant la casse`() = runBlocking {
        val id = pinnedId("a")
        assertEquals(LabelResult.SAVED, repository.setLabel(id, "  Mon IBAN \n"))
        assertEquals("Mon IBAN", awaitPinned { list -> list.single().label != null }.single().label)
    }

    @Test
    fun `setLabel remplace une etiquette existante`() = runBlocking {
        val id = pinnedId("a")
        repository.setLabel(id, "Avant")
        awaitPinned { list -> list.single().label == "Avant" }
        assertEquals(LabelResult.SAVED, repository.setLabel(id, "Après"))
        assertEquals("Après", awaitPinned { list -> list.single().label == "Après" }.single().label)
    }

    @Test
    fun `une etiquette de 15 caracteres est acceptee, de 16 refusee, vide refusee`() = runBlocking {
        val id = pinnedId("a")
        assertEquals(LabelResult.SAVED, repository.setLabel(id, "x".repeat(ClipboardItems.MAX_LABEL_CHARS)))
        assertEquals(LabelResult.TOO_LONG, repository.setLabel(id, "x".repeat(ClipboardItems.MAX_LABEL_CHARS + 1)))
        assertEquals(LabelResult.EMPTY, repository.setLabel(id, "   "))
        assertEquals(ClipboardItems.MAX_LABEL_CHARS, awaitPinned { list -> list.single().label != null }.single().label?.length)
    }

    @Test
    fun `deux elements peuvent avoir la meme etiquette`() = runBlocking {
        val a = pinnedId("a")
        val b = pinnedId("b")
        assertEquals(LabelResult.SAVED, repository.setLabel(a, "Perso"))
        assertEquals(LabelResult.SAVED, repository.setLabel(b, "Perso"))
        assertEquals(listOf("Perso", "Perso"), awaitPinned { list -> list.size == 2 && list.all { it.label != null } }.map { it.label })
    }

    @Test
    fun `clearLabel retire l etiquette et garde le texte`() = runBlocking {
        val id = pinnedId("a")
        repository.setLabel(id, "Perso")
        awaitPinned { list -> list.single().label == "Perso" }
        assertTrue(repository.clearLabel(id))
        val after = awaitPinned { list -> list.single().label == null }.single()
        assertEquals("a", after.text)
    }

    @Test
    fun `modifier le texte garde l etiquette`() = runBlocking {
        val id = pinnedId("a")
        repository.setLabel(id, "Perso")
        awaitPinned { list -> list.single().label == "Perso" }
        assertEquals(EditResult.SAVED, repository.updateText(id, "a modifié"))
        val after = awaitPinned { list -> list.single().text == "a modifié" }.single()
        assertEquals("Perso", after.label)
    }

    @Test
    fun `setLabel et clearLabel d un identifiant inconnu`() = runBlocking {
        assertEquals(LabelResult.NOT_FOUND, repository.setLabel(42, "Perso"))
        assertFalse(repository.clearLabel(42))
    }
}
