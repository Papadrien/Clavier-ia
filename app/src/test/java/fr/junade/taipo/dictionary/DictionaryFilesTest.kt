package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DictionaryFilesTest {

    @Test
    fun `deux fichiers par langue, aux noms du bucket`() {
        assertEquals("https://taipo-worker.junade-models.workers.dev/dictionaries-2026-11-12/fr.txt", DictionaryFiles.remoteUrl(KeyboardLanguage.FR, DictionaryFile.FULL))
        assertEquals(
            "https://taipo-worker.junade-models.workers.dev/dictionaries-2026-11-12/names/fr.txt",
            DictionaryFiles.remoteUrl(KeyboardLanguage.FR, DictionaryFile.NAMES),
        )
        assertEquals(2, DictionaryFile.entries.size)
    }

    @Test
    fun `installe seulement quand les deux fichiers sont presents et non vides`(@TempDir dir: File) {
        val language = KeyboardLanguage.EN
        assertFalse(DictionaryFiles.isInstalled(dir, language))
        DictionaryFile.entries.dropLast(1).forEach { write(DictionaryFiles.localFile(dir, language, it)) }
        assertEquals(listOf(DictionaryFile.FULL), DictionaryFiles.missing(dir, language))
        DictionaryFiles.localFile(dir, language, DictionaryFile.FULL).apply {
            parentFile?.mkdirs()
            writeText("")
        }
        assertFalse(DictionaryFiles.isInstalled(dir, language))
        write(DictionaryFiles.localFile(dir, language, DictionaryFile.FULL))
        assertTrue(DictionaryFiles.isInstalled(dir, language))
        assertFalse(DictionaryFiles.isInstalled(dir, KeyboardLanguage.FR))
    }

    @Test
    fun `l'ancienne liste de secours est supprimee, la liste complete reste`(@TempDir dir: File) {
        val language = KeyboardLanguage.FR
        val obsolete = File(DictionaryFiles.directory(dir), "fr_50k.txt").also { write(it) }
        val full = DictionaryFiles.localFile(dir, language, DictionaryFile.FULL).also { write(it) }
        assertTrue(DictionaryFiles.deleteObsoleteBackup(dir, language))
        assertFalse(obsolete.exists())
        assertTrue(full.isFile)
        assertFalse(DictionaryFiles.deleteObsoleteBackup(dir, language))
    }

    @Test
    fun `chaque langue a une taille indicative`() {
        KeyboardLanguage.entries.forEach { l -> DictionaryFile.entries.forEach { assertTrue(DictionaryFiles.approxBytes(l, it) > 0) } }
    }

    private fun write(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("x 1\n")
    }
}
