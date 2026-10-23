package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.SystemDictionaryWords.Entry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SystemDictionaryWordsTest {

    @Test
    fun `garde les mots de la langue et ceux valables partout`() {
        val entries = listOf(
            Entry("Junadé", 250, "fr_FR"),
            Entry("kubernetes", 250, null),
            Entry("gadget", 250, ""),
            Entry("whatsapp", 250, "en_US"),
            Entry("hallo", 250, "de"),
        )
        assertEquals(setOf("Junadé", "kubernetes", "gadget"), SystemDictionaryWords.select(entries, KeyboardLanguage.FR).toSet())
        assertEquals(setOf("kubernetes", "gadget", "whatsapp"), SystemDictionaryWords.select(entries, KeyboardLanguage.EN).toSet())
    }

    @Test
    fun `les plus frequents d'abord et sans doublon insensible a la casse`() {
        val entries = listOf(
            Entry("rare", 10, null),
            Entry("Taipo", 200, null),
            Entry("taipo", 250, "fr"),
            Entry("courant", 255, null),
        )
        assertEquals(listOf("courant", "taipo", "rare"), SystemDictionaryWords.select(entries, KeyboardLanguage.FR))
    }

    @Test
    fun `ecarte les mots invalides pour l'autocorrection`() {
        val entries = listOf(
            Entry("a", 100, null),
            Entry("deux mots", 100, null),
            Entry("abc123", 100, null),
            Entry("", 100, null),
            Entry("l’été", 100, null),
        )
        assertEquals(listOf("l'été"), SystemDictionaryWords.select(entries, KeyboardLanguage.FR))
    }

    @Test
    fun `plafonne le nombre de mots`() {
        val entries = (0 until SystemDictionaryWords.MAX_WORDS + 50).map { Entry("mot${letters(it)}", it, null) }
        val selected = SystemDictionaryWords.select(entries, KeyboardLanguage.FR)
        assertEquals(SystemDictionaryWords.MAX_WORDS, selected.size)
    }

    @Test
    fun `merge garde la graphie personnelle et ajoute les mots systeme absents`() {
        val merged = SystemDictionaryWords.merge(listOf("Adrien", "Junadé"), listOf("adrien", "taipo", "JUNADÉ", "kotlin"))
        assertEquals(listOf("Adrien", "Junadé", "taipo", "kotlin"), merged)
    }

    @Test
    fun `merge sans liste systeme renvoie la liste personnelle telle quelle`() {
        val personal = listOf("Adrien")
        assertSame(personal, SystemDictionaryWords.merge(personal, emptyList()))
        val system = listOf("taipo")
        assertSame(system, SystemDictionaryWords.merge(emptyList(), system))
    }

    @Test
    fun `le cache ne refait la fusion que si une liste change`() {
        val cache = SystemDictionaryWords.MergeCache()
        val personal = listOf("Adrien")
        val system = listOf("taipo")
        val first = cache.merge(personal, system)
        assertSame(first, cache.merge(personal, system))
        val second = cache.merge(listOf("Adrien", "Eve"), system)
        assertEquals(listOf("Adrien", "Eve", "taipo"), second)
    }

    /** Suite de lettres distincte pour chaque entier (les mots ne peuvent pas contenir de chiffre). */
    private fun letters(n: Int): String {
        var value = n
        val sb = StringBuilder()
        do {
            sb.append('a' + value % 26)
            value /= 26
        } while (value > 0)
        return sb.toString()
    }
}
