package fr.junade.taipo.emoji

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmojiCatalogTest {

    @Test
    fun `parse lit les categories dans l ordre et ignore les commentaires`() {
        val text = """
            # commentaire
            @smileys
            😀 😃
            😄
            @flags
            🇫🇷 🇩🇪
            #
        """.trimIndent()
        val result = EmojiCatalog.parse(text)
        assertEquals(listOf(EmojiCategoryId.SMILEYS, EmojiCategoryId.FLAGS), result.map { it.id })
        assertEquals(listOf("😀", "😃", "😄"), result[0].emojis)
        assertEquals(listOf("🇫🇷", "🇩🇪"), result[1].emojis)
    }

    @Test
    fun `parse ignore une categorie inconnue avec ses emojis`() {
        val result = EmojiCatalog.parse("@inconnue\n😀\n@food\n🍎")
        assertEquals(listOf(EmojiCategoryId.FOOD), result.map { it.id })
        assertEquals(listOf("🍎"), result[0].emojis)
    }

    @Test
    fun `une ligne qui commence par la touche dièse est une ligne d emojis`() {
        val keycap = "#\uFE0F\u20E3"
        val result = EmojiCatalog.parse("@symbols\n$keycap 🔟")
        assertEquals(listOf(keycap, "🔟"), result[0].emojis)
    }

    private fun realCatalog(): List<EmojiCategory> {
        val file = File("src/main/assets/emoji/emoji.txt")
        assertTrue(file.exists(), "asset introuvable : ${file.absolutePath}")
        return EmojiCatalog.parse(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `l asset reel contient toutes les categories, sans Recents, et aucune vide`() {
        val catalog = realCatalog()
        val expected = EmojiCategoryId.entries.filter { it != EmojiCategoryId.RECENT }
        assertEquals(expected, catalog.map { it.id })
        catalog.forEach { assertTrue(it.emojis.isNotEmpty(), "catégorie vide : ${it.id}") }
    }

    @Test
    fun `l asset reel n a aucun emoji en double ni d emoji ASCII`() {
        val all = realCatalog().flatMap { it.emojis }
        assertEquals(all.size, all.distinct().size)
        assertTrue(all.size > 1500, "catalogue trop petit : ${all.size}")
        // Chaque entrée est un emoji, jamais un texte : pas de lettre ASCII isolée (« :) », « xD »...).
        assertTrue(all.none { entry -> entry.all { it.code < 0x80 && it.isLetterOrDigit() } })
    }

    @Test
    fun `l asset reel contient les emojis de base`() {
        val all = realCatalog().flatMap { it.emojis }.toSet()
        listOf("😀", "😂", "❤️", "👍", "🎉", "🔥", "🇫🇷", "👩‍💻").forEach {
            assertTrue(it in all, "manquant : $it")
        }
    }
}
