package fr.junade.taipo.suggestion

import fr.junade.taipo.KeyboardLanguage
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmojiSuggesterTest {

    private val suggester = EmojiSuggester(
        french = EmojiSuggester.parse("🍕 pizza\n🐱 chat chaton\n☕ café\n😍 adore"),
        english = EmojiSuggester.parse("🍕 pizza\n🐱 cat\n❤️ love"),
    )

    private fun fr(text: String) = suggester.suggest(text, KeyboardLanguage.FR)
    private fun en(text: String) = suggester.suggest(text, KeyboardLanguage.EN)

    @Test
    fun `le dernier mot tape declenche l emoji`() {
        assertEquals("🍕", fr("je mange une pizza"))
        assertEquals("🐱", fr("mon chat"))
    }

    @Test
    fun `un seul espace apres le mot est accepte, pas deux`() {
        assertEquals("🍕", fr("une pizza "))
        assertNull(fr("une pizza  "))
    }

    @Test
    fun `apres une ponctuation, un retour a la ligne ou un emoji rien n est propose`() {
        assertNull(fr("une pizza."))
        assertNull(fr("une pizza !"))
        assertNull(fr("une pizza\n"))
        assertNull(fr("une pizza 🍕"))
        assertNull(fr("une pizza,"))
    }

    @Test
    fun `seul le dernier mot compte`() {
        assertNull(fr("une pizza pour demain"))
        assertNull(fr("pizza et"))
    }

    @Test
    fun `sans accent ni majuscule`() {
        assertEquals("☕", fr("un Café"))
        assertEquals("☕", fr("un cafe"))
        assertEquals("🍕", fr("PIZZA"))
    }

    @Test
    fun `le pluriel en s est reconnu`() {
        assertEquals("🐱", fr("des chats"))
        assertEquals("🍕", fr("deux pizzas"))
    }

    @Test
    fun `apres une apostrophe on prend le mot qui suit`() {
        assertEquals("😍", fr("j'adore"))
    }

    @Test
    fun `chaque langue a sa liste`() {
        assertEquals("🐱", fr("chat"))
        assertNull(en("chat"))
        assertEquals("🐱", en("cat"))
        assertNull(fr("cat"))
        assertEquals("❤️", en("love"))
    }

    @Test
    fun `texte vide, espace seul, mot inconnu ou trop long`() {
        assertNull(fr(""))
        assertNull(fr(" "))
        assertNull(fr("bonjour"))
        assertNull(fr("a".repeat(31)))
    }

    @Test
    fun `parse ignore commentaires et lignes vides, la premiere ligne l emporte`() {
        val map = EmojiSuggester.parse("# commentaire\n\n#\n🍕 pizza\n🍔 pizza burger\n")
        assertEquals("🍕", map["pizza"])
        assertEquals("🍔", map["burger"])
        assertEquals(2, map.size)
    }

    @Test
    fun `normalize retire les accents et passe en minuscules`() {
        assertEquals("cafe", EmojiSuggester.normalize("Café"))
        assertEquals("ca", EmojiSuggester.normalize("Ça"))
    }

    // --- Fichiers réels -------------------------------------------------------------------------

    private fun realCatalogEmojis(): Set<String> {
        val file = File("src/main/assets/emoji/emoji.txt")
        assertTrue(file.exists(), "asset introuvable : ${file.absolutePath}")
        return file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "#" && !it.startsWith("# ") && !it.startsWith("@") }
            .flatMap { it.split(Regex("\\s+")) }
            .toSet()
    }

    private fun realLines(name: String): List<String> {
        val file = File("src/main/assets/emoji/$name")
        assertTrue(file.exists(), "asset introuvable : ${file.absolutePath}")
        return file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "#" && !it.startsWith("# ") }
    }

    @Test
    fun `les fichiers reels ne proposent que des emojis du catalogue du panneau`() {
        val catalog = realCatalogEmojis()
        listOf("suggestions-fr.txt", "suggestions-en.txt").forEach { name ->
            realLines(name).forEach { line ->
                val emoji = line.split(Regex("\\s+")).first()
                assertTrue(emoji in catalog, "$name : $emoji absent du catalogue emoji.txt")
            }
        }
    }

    @Test
    fun `les fichiers reels n ont aucun mot en double ni de ligne sans mot`() {
        listOf("suggestions-fr.txt", "suggestions-en.txt").forEach { name ->
            val seen = HashSet<String>()
            realLines(name).forEach { line ->
                val tokens = line.split(Regex("\\s+"))
                assertTrue(tokens.size >= 2, "$name : ligne sans mot-clé : $line")
                tokens.drop(1).map { EmojiSuggester.normalize(it) }.forEach { key ->
                    assertTrue(seen.add(key), "$name : mot en double « $key »")
                }
            }
            assertTrue(seen.size > 150, "$name : liste trop courte (${seen.size} mots)")
        }
    }

    @Test
    fun `les fichiers reels donnent les suggestions attendues`() {
        val real = EmojiSuggester(
            french = EmojiSuggester.parse(File("src/main/assets/emoji/suggestions-fr.txt").readText(Charsets.UTF_8)),
            english = EmojiSuggester.parse(File("src/main/assets/emoji/suggestions-en.txt").readText(Charsets.UTF_8)),
        )
        assertEquals("🍕", real.suggest("j'ai mangé une pizza", KeyboardLanguage.FR))
        assertEquals("🙏", real.suggest("merci ", KeyboardLanguage.FR))
        assertEquals("🎂", real.suggest("joyeux anniversaire", KeyboardLanguage.FR))
        assertEquals("🍕", real.suggest("I love pizza", KeyboardLanguage.EN))
        assertEquals("🎂", real.suggest("happy birthday", KeyboardLanguage.EN))
    }
}
