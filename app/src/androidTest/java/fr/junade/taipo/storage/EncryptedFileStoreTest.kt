package fr.junade.taipo.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.junade.taipo.dictionary.DatabasePassphraseProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Audit BUG-02 et BUG-06 : [EncryptedFileStore] avec le vrai Android Keystore. Fichier, préférences et alias de test : les
 * données apprises de l'utilisateur ne sont jamais touchées.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedFileStoreTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private var content = StringBuilder()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        file = File(context.filesDir, FILE)
        file.delete()
        DatabasePassphraseProvider(context, PREFS, ALIAS).reset()
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
        DatabasePassphraseProvider(context, PREFS, ALIAS).reset()
    }

    private fun newStore(loaded: CountDownLatch = CountDownLatch(1)) = EncryptedFileStore(
        context = context,
        scope = scope,
        fileName = FILE,
        keyPrefsName = PREFS,
        keyAlias = ALIAS,
        tag = "EncryptedFileStoreTest",
        serialize = { content.toString() },
        merge = { text ->
            content.append(text)
            loaded.countDown()
        },
        clearModel = { content.clear() },
    )

    @Test
    fun le_contenu_ecrit_est_relu() {
        content.append("bonjour")
        newStore().apply {
            markDirty()
            flushBlocking()
        }
        assertTrue(file.isFile)
        content = StringBuilder()
        val loaded = CountDownLatch(1)
        newStore(loaded)
        assertTrue(loaded.await(5, TimeUnit.SECONDS))
        assertEquals("bonjour", content.toString())
    }

    @Test
    fun un_fichier_altere_est_efface() {
        content.append("bonjour")
        newStore().apply {
            markDirty()
            flushBlocking()
        }
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte() // étiquette GCM fausse : définitif
        file.writeBytes(bytes)
        content = StringBuilder()
        newStore()
        waitUntil { !file.exists() }
        assertFalse(file.exists())
    }

    @Test
    fun effacer_supprime_le_fichier() {
        content.append("bonjour")
        val store = newStore().apply {
            markDirty()
            flushBlocking()
        }
        store.clear()
        waitUntil { !file.exists() }
        assertFalse(file.exists())
        assertEquals("", content.toString())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private companion object {
        const val FILE = "encrypted_file_store_test.enc"
        const val PREFS = "encrypted_file_store_test_key"
        const val ALIAS = "taipo_encrypted_file_store_test_key"
    }
}
