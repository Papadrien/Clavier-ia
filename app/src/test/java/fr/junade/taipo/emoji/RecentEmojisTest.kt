package fr.junade.taipo.emoji

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecentEmojisTest {

    @Test
    fun `un nouvel emoji passe en tete`() {
        assertEquals(listOf("😀", "🎉"), RecentEmojis.add(listOf("🎉"), "😀"))
    }

    @Test
    fun `un emoji deja present remonte en tete sans doublon`() {
        assertEquals(listOf("🎉", "😀", "👍"), RecentEmojis.add(listOf("😀", "🎉", "👍"), "🎉"))
    }

    @Test
    fun `la liste est plafonnee et l ancien disparait`() {
        val full = (1..5).map { "e$it" }
        assertEquals(listOf("new", "e1", "e2"), RecentEmojis.add(full, "new", maxSize = 3))
    }

    @Test
    fun `encodage puis decodage redonne la liste`() {
        val list = listOf("😀", "👩‍💻", "🇫🇷", "1️⃣")
        assertEquals(list, RecentEmojis.decode(RecentEmojis.encode(list)))
    }

    @Test
    fun `decodage de rien ou de vide`() {
        assertEquals(emptyList<String>(), RecentEmojis.decode(null))
        assertEquals(emptyList<String>(), RecentEmojis.decode(""))
    }
}
