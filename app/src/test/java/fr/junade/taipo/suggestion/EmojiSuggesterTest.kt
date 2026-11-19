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
        others = mapOf(KeyboardLanguage.CS to EmojiSuggester.parse("🐶 pes psa\n🍕 pizza")),
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
    fun `les autres langues ont leur liste, sans regle de pluriel en s`() {
        fun cs(text: String) = suggester.suggest(text, KeyboardLanguage.CS)
        assertEquals("🐶", cs("můj pes"))
        assertEquals("🐶", cs("vidím psa"))
        assertNull(cs("pizzas"), "pas de pluriel en s hors français et anglais")
        assertNull(cs("chat"), "la liste française ne sert pas en tchèque")
        assertNull(suggester.suggest("pizza", KeyboardLanguage.PL), "langue sans liste : rien")
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

    /** Toutes les listes de mots-clés livrées (français, anglais et langues ajoutées le 12/11/2026). */
    private fun realListNames(): List<String> =
        File("src/main/assets/emoji").list().orEmpty().filter { it.startsWith("suggestions-") }.sorted()

    @Test
    fun `chaque langue ajoutee a sa liste de mots-cles`() {
        val names = realListNames()
        for (code in KeyboardLanguage.entries.map { it.tag }) {
            assertTrue("suggestions-$code.txt" in names, "suggestions-$code.txt manquant")
        }
    }

    @Test
    fun `les fichiers reels ne proposent que des emojis du catalogue du panneau`() {
        val catalog = realCatalogEmojis()
        realListNames().forEach { name ->
            realLines(name).forEach { line ->
                val emoji = line.split(Regex("\\s+")).first()
                assertTrue(emoji in catalog, "$name : $emoji absent du catalogue emoji.txt")
            }
        }
    }

    @Test
    fun `les fichiers reels n ont aucun mot en double ni de ligne sans mot`() {
        realListNames().forEach { name ->
            val seen = HashSet<String>()
            realLines(name).forEach { line ->
                val tokens = line.split(Regex("\\s+"))
                assertTrue(tokens.size >= 2, "$name : ligne sans mot-clé : $line")
                tokens.drop(1).map { EmojiSuggester.normalize(it) }.forEach { key ->
                    assertTrue(seen.add(key), "$name : mot en double « $key »")
                }
            }
            // Listes historiques (français, anglais) : plus de 150 mots ; listes ajoutées le 12/11/2026 : plus de 100.
            val minimum = if (name == "suggestions-fr.txt" || name == "suggestions-en.txt") 150 else 100
            assertTrue(seen.size > minimum, "$name : liste trop courte (${seen.size} mots)")
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

    @Test
    fun `les fichiers reels de l espagnol a du neerlandais donnent les suggestions attendues`() {
        fun list(code: String) = EmojiSuggester.parse(File("src/main/assets/emoji/suggestions-$code.txt").readText(Charsets.UTF_8))
        val languages = listOf(KeyboardLanguage.ES, KeyboardLanguage.DE, KeyboardLanguage.IT, KeyboardLanguage.PT, KeyboardLanguage.NL)
        val real = EmojiSuggester(french = emptyMap(), english = emptyMap(), others = languages.associateWith { list(it.tag) })
        assertEquals("🍕", real.suggest("quiero una pizza", KeyboardLanguage.ES))
        assertEquals("🎂", real.suggest("feliz cumpleaños ", KeyboardLanguage.ES))
        assertEquals("🐶", real.suggest("mein Hund", KeyboardLanguage.DE))
        assertEquals("🙏", real.suggest("Danke", KeyboardLanguage.DE))
        assertEquals("☕", real.suggest("un caffè", KeyboardLanguage.IT))
        assertEquals("🍕", real.suggest("pizze", KeyboardLanguage.IT))
        assertEquals("😘", real.suggest("beijos", KeyboardLanguage.PT))
        assertEquals("🙏", real.suggest("obrigada", KeyboardLanguage.PT))
        assertEquals("🚲", real.suggest("met de fiets", KeyboardLanguage.NL))
        assertEquals("🎂", real.suggest("ik ben jarig", KeyboardLanguage.NL))
        // Mots-outils très fréquents : pas d'emoji (« te » en espagnol et en italien, « dag » en néerlandais).
        assertNull(real.suggest("te", KeyboardLanguage.ES))
        assertNull(real.suggest("te", KeyboardLanguage.IT))
        assertNull(real.suggest("dag", KeyboardLanguage.NL))
    }
}
