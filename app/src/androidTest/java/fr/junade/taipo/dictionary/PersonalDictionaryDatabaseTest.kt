package fr.junade.taipo.dictionary

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.junade.taipo.testing.EncryptedDatabaseAssertions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lot 3.3 (T2) : [PersonalDictionaryDatabase] avec le vrai Room et le vrai SQLCipher. Base de test à
 * part : la vraie `personal_dictionary.db` de l'appareil n'est jamais touchée.
 */
@RunWith(AndroidJUnit4::class)
class PersonalDictionaryDatabaseTest {

    private lateinit var context: Context
    private val passphrase = "cle-de-test-dictionnaire-0123456789abcdef".toByteArray(Charsets.US_ASCII)

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    /** SupportOpenHelperFactory efface le tableau reçu après usage : toujours lui donner une copie. */
    private fun open(key: ByteArray = passphrase): PersonalDictionaryDatabase =
        PersonalDictionaryDatabase.create(context, key.copyOf(), TEST_DB)

    @Test
    fun ajout_unicite_et_plafond_avec_le_vrai_sql(): Unit = runBlocking {
        val database = open()
        try {
            val dao = database.personalWordDao()
            assertEquals(
                PersonalDictionary.AddResult.ADDED,
                dao.insertBounded(PersonalWordEntity("iphone", "iPhone"), maxWords = 2),
            )
            assertEquals(
                PersonalDictionary.AddResult.ALREADY_PRESENT,
                dao.insertBounded(PersonalWordEntity("iphone", "IPHONE"), maxWords = 2),
            )
            assertEquals(
                PersonalDictionary.AddResult.ADDED,
                dao.insertBounded(PersonalWordEntity("taipo", "Taipo"), maxWords = 2),
            )
            assertEquals(
                PersonalDictionary.AddResult.FULL,
                dao.insertBounded(PersonalWordEntity("junadé", "Junadé"), maxWords = 2),
            )
            assertEquals(2, dao.count())
            // La casse choisie par l'utilisateur est conservée.
            assertEquals(setOf("iPhone", "Taipo"), dao.observeAll().first().map { it.word }.toSet())
            assertEquals(1, dao.deleteByNormalized("iphone"))
            assertEquals(0, dao.deleteByNormalized("iphone"))
        } finally {
            database.close()
        }
    }

    @Test
    fun les_mots_survivent_a_la_fermeture_et_a_la_reouverture(): Unit = runBlocking {
        open().also {
            it.personalWordDao().insertBounded(PersonalWordEntity("café", "Café"), maxWords = 10)
            it.close()
        }
        val reopened = open()
        try {
            assertEquals(listOf("Café"), reopened.personalWordDao().observeAll().first().map { it.word })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun le_fichier_est_chiffre_sur_le_disque(): Unit = runBlocking {
        val marker = "MARQUEUR-CLAIR-b81d04"
        val database = open()
        try {
            database.personalWordDao().insertBounded(PersonalWordEntity(marker.lowercase(), marker), maxWords = 10)
            // Sans cette relecture, l'absence du marqueur sur le disque ne prouverait rien.
            assertEquals(marker, database.personalWordDao().observeAll().first().single().word)
        } finally {
            database.close()
        }

        // Le mot est stocké sous deux formes (normalisée en minuscules, et telle que saisie) : les deux sont testées.
        EncryptedDatabaseAssertions.assertEncryptedOnDisk(context.getDatabasePath(TEST_DB), marker)
        EncryptedDatabaseAssertions.assertEncryptedOnDisk(context.getDatabasePath(TEST_DB), marker.lowercase())
    }

    @Test
    fun une_mauvaise_cle_n_ouvre_pas_la_base(): Unit = runBlocking {
        open().also {
            it.personalWordDao().insertBounded(PersonalWordEntity("secret", "secret"), maxWords = 10)
            it.close()
        }

        val wrongKey = "autre-cle-de-test-dictionnaire-fedcba9876543210".toByteArray(Charsets.US_ASCII)
        val database = open(wrongKey)
        try {
            val outcome = runCatching { database.personalWordDao().count() }
            assertTrue("La base s'est ouverte avec une mauvaise clé", outcome.isFailure)
        } finally {
            runCatching { database.close() }
        }
    }

    private companion object {
        const val TEST_DB = "taipo_test_personal_dictionary.db"
    }
}
