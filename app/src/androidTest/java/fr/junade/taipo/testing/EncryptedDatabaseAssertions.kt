package fr.junade.taipo.testing

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Vérifications communes aux tests des bases chiffrées (lot 3.3, T2) : ce qui est sur le disque ne doit
 * ni ressembler à une base SQLite en clair, ni contenir les données écrites.
 */
object EncryptedDatabaseAssertions {

    /** En-tête d'un fichier SQLite non chiffré : « SQLite format 3 » suivi d'un octet nul. */
    private val PLAIN_SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    /**
     * La base doit être **fermée** (le journal WAL est alors reporté dans le fichier principal).
     * [marker] : texte ASCII distinctif écrit dans la base avant sa fermeture ; l'appelant a vérifié,
     * en le relisant par la base ouverte, qu'il y a bien été stocké (sinon ce test serait vide de sens).
     */
    fun assertEncryptedOnDisk(databaseFile: File, marker: String) {
        assertTrue("Fichier de base absent : $databaseFile", databaseFile.isFile)
        val header = databaseFile.inputStream().use { input -> ByteArray(PLAIN_SQLITE_HEADER.size).also { input.read(it) } }
        assertFalse(
            "${databaseFile.name} commence par l'en-tête SQLite en clair : la base n'est pas chiffrée",
            header.contentEquals(PLAIN_SQLITE_HEADER),
        )

        // Le marqueur ne doit apparaître ni dans le fichier principal ni dans les fichiers annexes (WAL, journal).
        val files = listOf(databaseFile) + listOf("-wal", "-journal", "-shm").map { File(databaseFile.path + it) }
        files.filter { it.isFile }.forEach { file ->
            val content = String(file.readBytes(), Charsets.ISO_8859_1)
            assertFalse("Le texte écrit apparaît en clair dans ${file.name}", content.contains(marker))
        }
    }
}
