package fr.junade.taipo.dictionary.download

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.DictionaryFile
import fr.junade.taipo.dictionary.DictionaryFiles
import fr.junade.taipo.dictionary.download.DictionaryContentCheck.Kind
import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DictionaryContentCheckTest {

    private fun file(dir: File, text: String) = File(dir, "f.txt").apply { writeText(text) }

    private fun words(count: Int) = (1..count).joinToString("\n") { "mot$it ${100_000 - it}" }

    private fun repoFile(path: String): File =
        listOf(File("../dictionaries/$path"), File("dictionaries/$path")).first { it.isFile }

    @Test
    fun `une liste de frequences est acceptee`(@TempDir dir: File) {
        assertTrue(DictionaryContentCheck.looksValid(file(dir, "# source\n" + words(12_000)), Kind.WORDS))
    }

    @Test
    fun `quelques lignes bizarres sont tolerees`(@TempDir dir: File) {
        val text = words(12_000) + "\n" + (1..100).joinToString("\n") { "ligne bizarre $it x" }
        assertTrue(DictionaryContentCheck.looksValid(file(dir, text), Kind.WORDS))
    }

    @Test
    fun `une liste trop courte ou au mauvais format est refusee`(@TempDir dir: File) {
        assertFalse(DictionaryContentCheck.looksValid(file(dir, words(50)), Kind.WORDS))
        val mixed = words(12_000) + "\n" + (1..2_000).joinToString("\n") { "pas une ligne $it" }
        assertFalse(DictionaryContentCheck.looksValid(file(dir, mixed), Kind.WORDS))
    }

    @Test
    fun `une page HTML, du JSON, un fichier vide ou binaire sont refuses`(@TempDir dir: File) {
        assertFalse(DictionaryContentCheck.looksValid(file(dir, "<!DOCTYPE html>\n" + words(12_000)), Kind.WORDS))
        assertFalse(DictionaryContentCheck.looksValid(file(dir, "{\"error\": 404}\n"), Kind.NAMES))
        assertFalse(DictionaryContentCheck.looksValid(file(dir, ""), Kind.WORDS))
        val binary = File(dir, "b.bin").apply { writeBytes(ByteArray(4_000) { (it * 37).toByte() }) }
        assertFalse(DictionaryContentCheck.looksValid(binary, Kind.NAMES))
        assertThrows(IOException::class.java) { DictionaryContentCheck.requireValid(file(dir, "<html>"), Kind.NAMES) }
    }

    @Test
    fun `une taille tres differente de la taille indicative est refusee`(@TempDir dir: File) {
        val f = file(dir, words(12_000))
        assertTrue(DictionaryContentCheck.looksValid(f, Kind.WORDS, f.length() * 2))
        assertFalse(DictionaryContentCheck.looksValid(f, Kind.WORDS, f.length() * 4))
        assertFalse(DictionaryContentCheck.looksValid(f, Kind.WORDS, f.length() / 4))
    }

    @Test
    fun `une liste de prenoms avec commentaire est acceptee`(@TempDir dir: File) {
        val names = (1..30).joinToString("\n") { "Prénom$it" }
        assertTrue(DictionaryContentCheck.looksValid(file(dir, "# Prénoms, une forme par ligne\n$names\n"), Kind.NAMES))
    }

    @Test
    fun `les fichiers du depot (ceux du serveur) passent tous le controle`() {
        for (language in KeyboardLanguage.entries) {
            val full = repoFile(DictionaryFile.FULL.relativePath(language))
            assertTrue(
                DictionaryContentCheck.looksValid(full, Kind.WORDS, DictionaryFiles.approxBytes(language, DictionaryFile.FULL)),
                full.path,
            )
            val names = repoFile(DictionaryFile.NAMES.relativePath(language))
            assertTrue(DictionaryContentCheck.looksValid(names, Kind.NAMES), names.path)
            if (language in DictionaryFiles.BIGRAM_LANGUAGES) {
                val bigrams = repoFile("bigrams/${language.tag}.txt")
                assertTrue(DictionaryContentCheck.looksValid(bigrams, Kind.BIGRAMS), bigrams.path)
            }
        }
    }
}
