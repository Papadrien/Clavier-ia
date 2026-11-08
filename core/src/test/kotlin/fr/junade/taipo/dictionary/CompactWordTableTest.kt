package fr.junade.taipo.dictionary

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompactWordTableTest {

    @Test
    fun `retrouve chaque mot et ignore les absents`() {
        val entries = mapOf("de" to 8_435_682L, "élève" to 12L, "aujourd'hui" to 90_000L, "œuvre" to 3L, "a" to 1L, "" to 7L)
        val table = CompactWordTable.from(entries)
        assertEquals(entries.size, table.size)
        for ((word, frequency) in entries) assertEquals(frequency, table.frequencyOf(word), word)
        for (absent in listOf("d", "dee", "eleve", "Élève", "aujourdhui", "zzz")) assertEquals(CompactWordTable.ABSENT, table.frequencyOf(absent), absent)
        assertEquals(CompactWordTable.ABSENT, CompactWordTable.from(emptyMap()).frequencyOf("x"))
    }

    @Test
    fun `vraie liste francaise - meme contenu que la table actuelle`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "dictionaries/fr.txt").isFile } ?: return
        val entries = WordForms.parseFrequencyBytes(File(root, "dictionaries/fr.txt").readBytes(), 5L)
        val table = CompactWordTable.from(entries)
        assertEquals(entries.size, table.size)
        for ((word, frequency) in entries) assertEquals(frequency, table.frequencyOf(word), word)
        assertEquals(CompactWordTable.ABSENT, table.frequencyOf("motquinexistepas"))
    }
}
