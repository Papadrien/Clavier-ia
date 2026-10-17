package fr.junade.taipo.dictionary.download

import fr.junade.taipo.dictionary.DictionaryFile
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException

class DictionaryContentCheckTest {

    private fun file(dir: File, text: String) = File(dir, "f.txt").apply { writeText(text) }

    @Test
    fun `une liste de frequences est acceptee`(@TempDir dir: File) {
        assertTrue(DictionaryContentCheck.looksValid(file(dir, "de 8435682\nje 8308698\n"), DictionaryFile.FULL))
    }

    @Test
    fun `une page HTML ou un fichier vide sont refuses`(@TempDir dir: File) {
        assertFalse(DictionaryContentCheck.looksValid(file(dir, "<!DOCTYPE html>\n<html>"), DictionaryFile.FULL))
        assertFalse(DictionaryContentCheck.looksValid(file(dir, ""), DictionaryFile.BACKUP))
        assertThrows(IOException::class.java) { DictionaryContentCheck.requireValid(file(dir, "<html>"), DictionaryFile.NAMES) }
    }

    @Test
    fun `une liste de prenoms avec commentaire est acceptee`(@TempDir dir: File) {
        assertTrue(DictionaryContentCheck.looksValid(file(dir, "# Prénoms\nAdrien\nAlexandre\n"), DictionaryFile.NAMES))
    }
}
