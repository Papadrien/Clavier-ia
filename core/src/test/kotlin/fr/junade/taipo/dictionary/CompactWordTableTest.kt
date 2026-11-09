package fr.junade.taipo.dictionary

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `se lit comme la map d'origine`() {
        val entries = mapOf("de" to 8_435_682L, "élève" to 12L, "œuvre" to 0L)
        val table = CompactWordTable.from(entries)
        assertEquals(entries, table)
        assertEquals(entries.keys, table.keys)
        assertEquals(12L, table["élève"])
        assertNull(table["eleve"])
        assertTrue("œuvre" in table)
        assertFalse("oeuvre" in table)
        assertEquals(0L, table.getValue("œuvre"))
        assertEquals(StringBuilder("de").let { table.frequencyOf(it) }, 8_435_682L)
        assertSame(table, CompactWordTable.from(table))
        assertTrue(CompactWordTable.from(emptyMap()).isEmpty())
    }

    @Test
    fun `garde les frequences qui depassent un Int`() {
        val entries = mapOf("grand" to 5_000_000_000L, "petit" to 3L, "negatif" to -2L)
        val table = CompactWordTable.from(entries)
        assertEquals(entries, table)
        assertEquals(5_000_000_000L, table.frequencyOf("grand"))
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
