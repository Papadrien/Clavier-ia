package fr.junade.taipo.clipboard

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.junade.taipo.testing.EncryptedDatabaseAssertions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lot 3.3 (T2) : [ClipboardDatabase] avec le vrai Room, le vrai SQLCipher et du vrai disque.
 *
 * Ce que les tests JVM ne peuvent pas voir : le SQL généré par Room, la migration 1 → 2, la validation
 * du schéma par Room et le chiffrement effectif du fichier.
 *
 * La base de test porte un nom à part ([TEST_DB]) : la vraie `clipboard.db` de l'appareil (éléments
 * épinglés de l'utilisateur) n'est jamais ouverte ni supprimée.
 *
 * Migration : la base de version 1 est reconstruite à la main avec le DDL figé de l'époque (table
 * `pinned_clips` seule). C'est équivalent à un `MigrationTestHelper` alimenté par `schemas/…/1.json`, sans
 * en dépendre. À l'ouverture, Room exécute [ClipboardDatabase.MIGRATION_1_2] puis **compare le résultat
 * au schéma attendu des entités** : un DDL de migration qui diverge fait échouer ces tests.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardDatabaseTest {

    private lateinit var context: Context
    private val passphrase = "cle-de-test-clipboard-0123456789abcdef".toByteArray(Charsets.US_ASCII)

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
    private fun open(key: ByteArray = passphrase): ClipboardDatabase =
        ClipboardDatabase.create(context, key.copyOf(), TEST_DB)

    private fun createVersion1Database(withPinned: Boolean) {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                // DDL de la version 1, tel que Room l'avait généré pour PinnedClipEntity.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pinned_clips` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`pinnedAt` INTEGER NOT NULL, " +
                        "`label` TEXT)",
                )
                if (withPinned) {
                    db.execSQL("INSERT INTO pinned_clips (text, pinnedAt, label) VALUES ('IBAN perso', 1000, 'Banque')")
                    db.execSQL("INSERT INTO pinned_clips (text, pinnedAt, label) VALUES ('Adresse', 2000, NULL)")
                }
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(callback)
            .build()
        val helper = SupportOpenHelperFactory(passphrase.copyOf()).create(configuration)
        helper.writableDatabase // force la création
        helper.close()
    }

    private fun userVersion(database: ClipboardDatabase): Int =
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    @Test
    fun migration_1_vers_2_conserve_les_elements_epingles(): Unit = runBlocking {
        createVersion1Database(withPinned = true)

        val database = open()
        try {
            val pinned = database.pinnedClipDao().observeAll().first()
            // observeAll : du plus récemment épinglé au plus ancien.
            assertEquals(listOf("Adresse", "IBAN perso"), pinned.map { it.text })
            assertEquals(listOf(null, "Banque"), pinned.map { it.label })
            assertEquals(listOf(2000L, 1000L), pinned.map { it.pinnedAt })
            assertEquals(2, userVersion(database))
        } finally {
            database.close()
        }
    }

    @Test
    fun migration_1_vers_2_cree_une_table_historique_utilisable(): Unit = runBlocking {
        createVersion1Database(withPinned = false)

        val database = open()
        try {
            val dao = database.clipHistoryDao()
            assertTrue(dao.observeAll().first().isEmpty())

            val id = dao.insert(ClipHistoryEntity(text = "copie récente", copiedAt = 5_000L, sensitive = true))
            assertTrue(id > 0)
            val rows = dao.observeAll().first()
            assertEquals(1, rows.size)
            assertEquals("copie récente", rows[0].text)
            assertEquals(5_000L, rows[0].copiedAt)
            assertTrue(rows[0].sensitive)
        } finally {
            database.close()
        }
    }

    @Test
    fun une_base_neuve_s_ouvre_directement_en_version_2(): Unit = runBlocking {
        val database = open()
        try {
            assertEquals(2, userVersion(database))
            val pinnedId = database.pinnedClipDao().insert(PinnedClipEntity(text = "épinglé", pinnedAt = 1L, label = "L"))
            assertTrue(pinnedId > 0)
            assertEquals(1, database.pinnedClipDao().count())
            assertNotNull(database.clipHistoryDao())
        } finally {
            database.close()
        }
    }

    @Test
    fun les_donnees_survivent_a_la_fermeture_et_a_la_reouverture(): Unit = runBlocking {
        open().also { database ->
            database.pinnedClipDao().insert(PinnedClipEntity(text = "durable", pinnedAt = 42L))
            database.close()
        }
        val reopened = open()
        try {
            assertEquals(listOf("durable"), reopened.pinnedClipDao().observeAll().first().map { it.text })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun le_fichier_est_chiffre_sur_le_disque(): Unit = runBlocking {
        val marker = "MARQUEUR-CLAIR-7f3a9c"
        val database = open()
        try {
            database.pinnedClipDao().insert(PinnedClipEntity(text = marker, pinnedAt = 1L))
            database.clipHistoryDao().insert(ClipHistoryEntity(text = "$marker-historique", copiedAt = 1L))
            // Sans cette relecture, l'absence du marqueur sur le disque ne prouverait rien.
            assertEquals(marker, database.pinnedClipDao().observeAll().first().single().text)
        } finally {
            database.close()
        }

        EncryptedDatabaseAssertions.assertEncryptedOnDisk(context.getDatabasePath(TEST_DB), marker)
    }

    @Test
    fun une_mauvaise_cle_n_ouvre_pas_la_base(): Unit = runBlocking {
        open().also {
            it.pinnedClipDao().insert(PinnedClipEntity(text = "secret", pinnedAt = 1L))
            it.close()
        }

        val wrongKey = "autre-cle-de-test-clipboard-fedcba9876543210".toByteArray(Charsets.US_ASCII)
        val database = open(wrongKey)
        try {
            val outcome = runCatching { database.pinnedClipDao().count() }
            assertTrue("La base s'est ouverte avec une mauvaise clé", outcome.isFailure)
            assertNull(outcome.getOrNull())
        } finally {
            runCatching { database.close() }
        }
    }

    private companion object {
        const val TEST_DB = "taipo_test_clipboard.db"
    }
}
