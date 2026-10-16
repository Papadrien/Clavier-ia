package fr.junade.taipo.dictionary

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Listes de prénoms (dictionaries/names/<code>.txt) : une par langue du clavier, bien formées, sans doublon. */
class ProperNamesAssetsTest {

    private fun names(language: String): List<String> {
        val candidates = listOf(
            File("src/main/assets/dictionaries/names/$language.txt"),
            File("app/src/main/assets/dictionaries/names/$language.txt"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: fail("Liste de prénoms introuvable : $language")
        return file.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @ParameterizedTest(name = "prenoms {0}")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "nl", "pt"])
    fun `la liste de prenoms est complete et bien formee`(language: String) {
        val list = names(language)
        assertTrue(list.size >= 60, "$language : seulement ${list.size} prénoms")
        list.forEach {
            assertTrue(it.first().isUpperCase(), "$language : « $it » doit commencer par une majuscule")
            assertTrue(it.all { c -> c.isLetter() }, "$language : « $it » ne doit contenir que des lettres")
        }
        assertEquals(list.size, list.map { it.lowercase() }.toSet().size, "$language : doublon dans la liste")
    }
}
