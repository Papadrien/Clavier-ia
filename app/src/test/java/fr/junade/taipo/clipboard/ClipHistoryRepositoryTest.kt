package fr.junade.taipo.clipboard

import fr.junade.taipo.clipboard.ClipboardItems.EditResult
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
 * Story 2.9 : teste [ClipHistoryRepository] et les règles transactionnelles réelles de
 * [ClipHistoryDao] (`record`, `updateText`) avec un DAO en mémoire (le SQL généré par Room, la
 * migration et le chiffrement SQLCipher ne sont pas testables en JVM).
 */
class ClipHistoryRepositoryTest {

    private class FakeDao : ClipHistoryDao() {
        val state = MutableStateFlow<List<ClipHistoryEntity>>(emptyList())
        private var nextId = 1L

        // L'ouverture du dépôt purge en tâche de fond pendant que les tests écrivent : lectures-écritures atomiques.
        private inline fun <T> locked(block: () -> T): T = synchronized(this) { block() }

        private fun sorted(list: List<ClipHistoryEntity>) =
            list.sortedWith(compareByDescending<ClipHistoryEntity> { it.copiedAt }.thenByDescending { it.id })

        override fun observeAll(): Flow<List<ClipHistoryEntity>> = state.map { sorted(it) }

        override suspend fun findIdByText(text: String): Long? = state.value.firstOrNull { it.text == text }?.id

        override suspend fun insert(entity: ClipHistoryEntity): Long = locked {
            val id = nextId++
            state.value = state.value + entity.copy(id = id)
            id
        }

        override suspend fun deleteOlderThan(threshold: Long): Int = locked {
            val before = state.value.size
            state.value = state.value.filter { it.copiedAt > threshold }
            before - state.value.size
        }

        override suspend fun trimTo(keep: Int): Int = locked {
            val before = state.value.size
            state.value = sorted(state.value).take(keep)
            before - state.value.size
        }

        override suspend fun touch(text: String, copiedAt: Long): Int = locked {
            var changed = 0
            state.value = state.value.map {
                if (it.text == text && it.copiedAt < copiedAt) {
                    changed++
                    it.copy(copiedAt = copiedAt)
                } else {
                    it
                }
            }
            changed
        }

        override suspend fun markSensitive(text: String): Int = locked {
            var changed = 0
            state.value = state.value.map {
                if (it.text == text) {
                    changed++
                    it.copy(sensitive = true)
                } else {
                    it
                }
            }
            changed
        }

        override suspend fun setText(id: Long, text: String): Int = locked {
            if (state.value.none { it.id == id }) {
                0
            } else {
                state.value = state.value.map { if (it.id == id) it.copy(text = text) else it }
                1
            }
        }

        override suspend fun deleteOthersWithText(text: String, id: Long): Int = locked {
            val before = state.value.size
            state.value = state.value.filterNot { it.text == text && it.id != id }
            before - state.value.size
        }

        override suspend fun deleteById(id: Long): Int = locked {
            val before = state.value.size
            state.value = state.value.filterNot { it.id == id }
            before - state.value.size
        }
    }

    private var now = 100 * HOUR
    private val dao = FakeDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = ClipHistoryRepository(scope, openDao = { dao }, clock = { now })

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitHistory(predicate: (List<ClipHistoryEntry>) -> Boolean): List<ClipHistoryEntry> =
        withTimeout(5_000) { repository.history.first(predicate) }

    private fun texts() = dao.state.value.sortedWith(compareByDescending<ClipHistoryEntity> { it.copiedAt }.thenByDescending { it.id }).map { it.text }

    @Test
    fun `record ajoute une copie et history la reflete`() = runBlocking {
        assertTrue(repository.record("Bonjour", copiedAtMillis = now - 1_000, sensitive = false))
        assertEquals(listOf("Bonjour"), awaitHistory { it.isNotEmpty() }.map { it.text })
        assertEquals(now - 1_000, repository.snapshot().single().copiedAtMillis)
    }

    @Test
    fun `la copie la plus recente vient en premier`() = runBlocking {
        repository.record("a", now - 3_000, false)
        repository.record("b", now - 1_000, false)
        repository.record("c", now - 2_000, false)
        assertEquals(listOf("b", "c", "a"), awaitHistory { it.size == 3 }.map { it.text })
    }

    @Test
    fun `un texte vide ou trop long n est pas enregistre`() = runBlocking {
        assertFalse(repository.record("  ", now, false))
        assertFalse(repository.record("a".repeat(ClipboardItems.MAX_HISTORY_CHARS + 1), now, false))
        assertTrue(repository.record("a".repeat(ClipboardItems.MAX_HISTORY_CHARS), now, false))
        assertEquals(1, dao.state.value.size)
    }

    @Test
    fun `un contenu sensible est enregistre comme les autres, avec son drapeau`() = runBlocking {
        assertTrue(repository.record("secret", now - 1, sensitive = true))
        assertTrue(repository.record("normal", now - 2, sensitive = false))
        val history = awaitHistory { it.size == 2 }
        assertTrue(history.first { it.text == "secret" }.sensitive)
        assertFalse(history.first { it.text == "normal" }.sensitive)
    }

    @Test
    fun `une copie deja expiree n est pas enregistree`() = runBlocking {
        assertFalse(repository.record("vieille", copiedAtMillis = now - HOUR, sensitive = false))
        assertTrue(repository.record("juste valide", copiedAtMillis = now - HOUR + 1, sensitive = false))
        assertEquals(listOf("juste valide"), texts())
    }

    @Test
    fun `sans horodatage la copie est datee de maintenant`() = runBlocking {
        repository.record("a", copiedAtMillis = 0, sensitive = false)
        assertEquals(now, awaitHistory { it.isNotEmpty() }.single().copiedAtMillis)
    }

    @Test
    fun `un horodatage dans le futur est ramene a maintenant`() = runBlocking {
        repository.record("a", copiedAtMillis = now + 10 * HOUR, sensitive = false)
        assertEquals(now, awaitHistory { it.isNotEmpty() }.single().copiedAtMillis)
    }

    @Test
    fun `le plafond de 20 entrees supprime les plus anciennes`() = runBlocking {
        repeat(ClipboardItems.MAX_HISTORY + 5) { repository.record("copie $it", now - 100_000 + it * 1_000L, false) }
        // On attend l'état final, pas seulement la bonne taille : l'historique réémis après la 20e copie a déjà
        // 20 lignes (« copie 0 » à « copie 19 ») alors que les 5 dernières copies ne sont pas encore reflétées.
        val newest = "copie ${ClipboardItems.MAX_HISTORY + 4}"
        val history = awaitHistory { it.size == ClipboardItems.MAX_HISTORY && it.first().text == newest }
        assertEquals(ClipboardItems.MAX_HISTORY, history.size)
        assertEquals("copie ${ClipboardItems.MAX_HISTORY + 4}", history.first().text)
        assertEquals("copie 5", history.last().text)
    }

    @Test
    fun `recopier un texte deja present le remonte en tete sans doublon`() = runBlocking {
        repository.record("a", now - 3_000, false)
        repository.record("b", now - 2_000, false)
        repository.record("a", now - 1_000, false)
        assertEquals(listOf("a", "b"), texts())
        assertEquals(now - 1_000, dao.state.value.first { it.text == "a" }.copiedAt)
    }

    @Test
    fun `sans horodatage une copie deja connue garde son heure d origine`() = runBlocking {
        repository.record("a", now - 30 * MINUTE, false)
        now += 10 * MINUTE
        repository.record("a", copiedAtMillis = 0, sensitive = false)
        assertEquals(1, dao.state.value.size)
        assertEquals(now - 40 * MINUTE, dao.state.value.single().copiedAt)
    }

    @Test
    fun `recopier un texte avec un horodatage plus ancien ne le fait pas reculer`() = runBlocking {
        repository.record("a", now - 1_000, false)
        repository.record("a", now - 5_000, false)
        assertEquals(now - 1_000, dao.state.value.single().copiedAt)
    }

    @Test
    fun `recopier un texte deja present en sensible le marque sensible`() = runBlocking {
        repository.record("a", now - 2_000, sensitive = false)
        repository.record("a", now - 1_000, sensitive = true)
        assertTrue(dao.state.value.single().sensitive)
    }

    @Test
    fun `chaque copie purge les lignes de plus d une heure`() = runBlocking {
        repository.record("ancienne", now - 10, false)
        now += HOUR
        repository.record("nouvelle", now - 5, false)
        assertEquals(listOf("nouvelle"), texts())
    }

    @Test
    fun `purgeExpired supprime les lignes de plus d une heure`() = runBlocking {
        repository.record("a", now - 20 * MINUTE, false)
        repository.record("b", now - 10, false)
        now += HOUR - 15 * MINUTE
        repository.purgeExpired()
        assertEquals(listOf("b"), texts())
    }

    @Test
    fun `la base ouverte purge les lignes expirees au demarrage`() = runBlocking {
        val seeded = FakeDao()
        seeded.insert(ClipHistoryEntity(text = "expiree", copiedAt = now - 2 * HOUR))
        seeded.insert(ClipHistoryEntity(text = "valide", copiedAt = now - 10 * MINUTE))
        val local = ClipHistoryRepository(scope, openDao = { seeded }, clock = { now })
        withTimeout(5_000) { local.history.first { it.isNotEmpty() } }
        assertEquals(listOf("valide"), seeded.state.value.map { it.text })
    }

    @Test
    fun `remove supprime une ligne, un id inconnu retourne faux`() = runBlocking {
        repository.record("a", now - 1, false)
        val id = awaitHistory { it.isNotEmpty() }.single().id
        assertTrue(repository.remove(id))
        assertFalse(repository.remove(id))
        assertTrue(dao.state.value.isEmpty())
    }

    // Modification

    @Test
    fun `updateText remplace le texte en gardant l heure de copie`() = runBlocking {
        repository.record("a", now - 5_000, false)
        val id = awaitHistory { it.isNotEmpty() }.single().id
        assertEquals(EditResult.SAVED, repository.updateText(id, "a modifié"))
        val after = awaitHistory { list -> list.any { it.text == "a modifié" } }.single()
        assertEquals(id, after.id)
        assertEquals(now - 5_000, after.copiedAtMillis)
    }

    @Test
    fun `updateText refuse un texte vide ou trop long et un id inconnu`() = runBlocking {
        repository.record("a", now - 1, false)
        val id = awaitHistory { it.isNotEmpty() }.single().id
        assertEquals(EditResult.EMPTY, repository.updateText(id, "  "))
        assertEquals(EditResult.TOO_LONG, repository.updateText(id, "x".repeat(ClipboardItems.MAX_PINNED_CHARS + 1)))
        assertEquals(EditResult.NOT_FOUND, repository.updateText(id + 100, "texte"))
        assertEquals(listOf("a"), texts())
    }

    @Test
    fun `modifier vers le texte d une autre ligne fusionne les deux`() = runBlocking {
        repository.record("a", now - 3_000, false)
        repository.record("b", now - 1_000, false)
        val ids = awaitHistory { it.size == 2 }.associate { it.text to it.id }
        assertEquals(EditResult.SAVED, repository.updateText(ids.getValue("a"), "b"))
        assertEquals(listOf("b"), texts())
        assertEquals(ids.getValue("a"), dao.state.value.single().id)
    }

    @Test
    fun `updateText d un id inconnu ne fusionne rien`() = runBlocking {
        repository.record("b", now - 1_000, false)
        assertEquals(EditResult.NOT_FOUND, repository.updateText(999, "b"))
        assertEquals(listOf("b"), texts())
    }

    @Test
    fun `replaceText met a jour la ligne de la derniere copie modifiee`() = runBlocking {
        repository.record("Bonjour", now - 1_000, false)
        assertTrue(repository.replaceText("Bonjour", "Bonjour à tous"))
        assertEquals(listOf("Bonjour à tous"), texts())
    }

    @Test
    fun `replaceText sans ligne correspondante ou avec un texte refuse ne fait rien`() = runBlocking {
        repository.record("a", now - 1_000, false)
        assertFalse(repository.replaceText("absent", "autre"))
        assertFalse(repository.replaceText("a", "  "))
        assertEquals(listOf("a"), texts())
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
    }
}
