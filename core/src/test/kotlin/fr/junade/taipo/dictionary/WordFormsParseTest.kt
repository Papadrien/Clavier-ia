package fr.junade.taipo.dictionary

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** La lecture rapide sur octets ([WordForms.parseFrequencyBytes]) donne exactement le même résultat que la lecture par lignes. */
class WordFormsParseTest {

    private fun both(text: String, minFrequency: Long = 0L) {
        val expected = WordForms.parseFrequencyLines(text.lineSequence(), minFrequency)
        val actual = WordForms.parseFrequencyBytes(text.toByteArray(Charsets.UTF_8), minFrequency)
        assertEquals(expected, actual, "texte : ${text.replace("\n", "⏎")}")
    }

    @Test
    fun `cas ordinaires et pieges`() {
        both("de 8435682\nje 8308698\nest 6942248\n")
        both("de 10\r\nje 3\r\n") // fins de ligne Windows
        both("  bonjour   42  \n\n\t\nsalut\n") // espaces autour, lignes vides, mot sans fréquence
        both("aujourd'hui 5\npomme de terre 7\n") // apostrophe, expression avec espaces
        both("élève 12\nça 9\nœuvre 3\nstraße 4\n") // caractères accentués (UTF-8 sur plusieurs octets)
        both("mot abc\nmot2 12x\n") // fréquence non numérique : la ligne entière est le mot
        both("doublon 3\ndoublon 9\ndoublon 1\n") // doublons : la plus haute fréquence
        both("signe +5\nnegatif -3\n", minFrequency = -10L)
        both("trop 99999999999999999999\n") // dépassement : traité comme un mot sans fréquence
        both("rare 4\ncourant 5\nfrequent 500\n", minFrequency = 5L)
        both(" 12\n")
        both("")
        both("dernier 7") // pas de retour à la ligne final
    }

    @Test
    fun `vraies listes des sept langues - meme resultat`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "dictionaries/fr.txt").isFile } ?: return
        for (language in listOf("fr", "en", "es", "de", "it", "pt", "nl")) {
            val file = File(root, "dictionaries/$language.txt")
            if (!file.isFile) continue
            val expected = file.bufferedReader(Charsets.UTF_8).useLines { WordForms.parseFrequencyLines(it, 5L) }
            val actual = WordForms.parseFrequencyBytes(file.readBytes(), 5L, expectedEntries = (file.length() / 35).toInt())
            assertEquals(expected.size, actual.size, language)
            assertEquals(expected, actual, language)
        }
    }
}
