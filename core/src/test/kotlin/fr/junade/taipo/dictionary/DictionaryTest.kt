package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryTest {

    private val dictionary = Dictionary(listOf("bonjour", "merci", "salut", "chat", "chien", "vraiment"))

    @Test
    fun `contains est insensible a la casse`() {
        assertTrue(dictionary.contains("bonjour"))
        assertTrue(dictionary.contains("Bonjour"))
        assertTrue(dictionary.contains("BONJOUR"))
        assertFalse(dictionary.contains("bonjoure"))
    }

    @Test
    fun `correctionFor renvoie null si le mot est deja correct`() {
        assertNull(dictionary.correctionFor("bonjour"))
    }

    @Test
    fun `correctionFor corrige une faute de frappe proche`() {
        assertEquals("bonjour", dictionary.correctionFor("bnojour"))
        assertEquals("merci", dictionary.correctionFor("mreci"))
    }

    @Test
    fun `correctionFor reapplique la majuscule du mot d origine`() {
        assertEquals("Bonjour", dictionary.correctionFor("Bnojour"))
    }

    @Test
    fun `correctionFor renvoie null pour un mot trop court`() {
        assertNull(dictionary.correctionFor("a"))
    }

    @Test
    fun `correctionFor renvoie null pour un mot contenant un chiffre`() {
        assertNull(dictionary.correctionFor("ch4t"))
    }

    @Test
    fun `correctionFor renvoie null pour un acronyme tout en majuscules`() {
        assertNull(dictionary.correctionFor("SVP"))
    }

    @Test
    fun `correctionFor renvoie null si aucun candidat n est assez proche`() {
        assertNull(dictionary.correctionFor("ordinateur"))
    }

    @Test
    fun `correctionFor renvoie null en cas d ambiguite entre deux candidats aussi proches`() {
        // "chXt" est à distance 1 aussi bien de "chat" que de "chien" ? Non — on
        // construit plutôt un cas explicite avec deux mots équidistants du mot tapé.
        val ambiguousDictionary = Dictionary(listOf("chat", "chah"))
        assertNull(ambiguousDictionary.correctionFor("cha"))
    }

    @Test
    fun `correctionFor ne corrige pas un mot du dictionnaire personnel`() {
        assertNull(dictionary.correctionFor("chatt", personalWords = listOf("chatt")))
        assertNull(dictionary.correctionFor("Chatt", personalWords = listOf("chatt")))
    }

    @Test
    fun `correctionFor ne corrige pas un mot personnel apres une elision`() {
        val personal = listOf("Taipo", "Adrien")
        assertNull(dictionary.correctionFor("l'Taipo", personalWords = personal))
        assertNull(dictionary.correctionFor("d'Adrien", personalWords = personal))
        assertNull(dictionary.correctionFor("qu'adrien", personalWords = personal))
    }

    @Test
    fun `correctionFor ne corrige pas un mot personnel suivi du possessif anglais`() {
        assertNull(dictionary.correctionFor("Taipo's", personalWords = listOf("Taipo")))
    }

    @Test
    fun `les completions personnelles sont proposees apres une elision`() {
        val slots = dictionary.suggestionSlotsFor("l'Ta", personalWords = listOf("Taipo"))
        assertEquals("l'Taipo", slots.firstOrNull { it != null }?.text)
    }

    @Test
    fun `correctionFor utilise un mot personnel comme candidat`() {
        assertEquals("Taipo", dictionary.correctionFor("Taipoo", personalWords = listOf("Taipo")))
    }

    @Test
    fun `correctionFor conserve la casse enregistree d un mot personnel`() {
        assertEquals("iPhone", dictionary.correctionFor("iPhoen", personalWords = listOf("iPhone")))
    }

    @Test
    fun `correctionFor reapplique la majuscule pour un mot personnel en minuscules`() {
        assertEquals("Zorglub", dictionary.correctionFor("Zroglub", personalWords = listOf("zorglub")))
    }

    @Test
    fun `correctionFor prefere le mot personnel a un mot du dictionnaire a distance egale`() {
        // Story 1.14 : avant les fréquences, cette égalité donnait null ; le mot ajouté volontairement gagne désormais.
        val base = Dictionary(listOf("chat"))
        assertEquals("chot", base.correctionFor("chit", personalWords = listOf("chot")))
    }

    @Test
    fun `levenshtein calcule la distance d edition classique`() {
        assertEquals(0, Dictionary.levenshtein("chat", "chat"))
        assertEquals(1, Dictionary.levenshtein("chat", "chats"))
        assertEquals(1, Dictionary.levenshtein("chat", "chot"))
        assertEquals(3, Dictionary.levenshtein("kitten", "sitting"))
    }

    @Test
    fun `damerauLevenshtein compte une inversion pour un`() {
        assertEquals(0, Dictionary.damerauLevenshtein("chat", "chat"))
        assertEquals(1, Dictionary.damerauLevenshtein("teh", "the"))
        assertEquals(1, Dictionary.damerauLevenshtein("bnojour", "bonjour"))
        assertEquals(2, Dictionary.levenshtein("teh", "the"))
        // Comme Levenshtein quand il n'y a pas d'inversion.
        assertEquals(1, Dictionary.damerauLevenshtein("chat", "chats"))
        assertEquals(3, Dictionary.damerauLevenshtein("kitten", "sitting"))
        // Variante OSA : une même lettre n'est pas modifiée deux fois ("ca" -> "abc" = 3).
        assertEquals(3, Dictionary.damerauLevenshtein("ca", "abc"))
        assertEquals(4, Dictionary.damerauLevenshtein("", "abcd"))
    }

    @Test
    fun `applyOriginalCasing majuscule la correction si l original en avait une`() {
        assertEquals("Merci", Dictionary.applyOriginalCasing("Mreci", "merci"))
        assertEquals("merci", Dictionary.applyOriginalCasing("mreci", "merci"))
    }

    @Test
    fun `a distance egale le mot le plus frequent l emporte`() {
        val d = Dictionary.withFrequencies(mapOf("chat" to 900L, "chah" to 10L))
        assertEquals("chat", d.correctionFor("cha"))
    }

    @Test
    fun `la distance prime sur la frequence`() {
        // "chatt" est à 1 de "chat" mais à 2 de "chattes" : le plus proche gagne malgré la fréquence.
        val d = Dictionary.withFrequencies(mapOf("chattes" to 1_000_000L, "chat" to 5L))
        assertEquals("chat", d.correctionFor("chatt"))
    }

    @Test
    fun `une inversion de deux lettres coute une seule modification`() {
        // "teh" est à 1 de "the" (inversion) comme de "ten" (substitution) : à distance égale,
        // le mot le plus fréquent l'emporte.
        val d = Dictionary.withFrequencies(mapOf("the" to 22_761_659L, "ten" to 300_000L))
        assertEquals("the", d.correctionFor("teh"))
    }

    @Test
    fun `une inversion l emporte sur un mot plus frequent a distance 2`() {
        val d = Dictionary.withFrequencies(mapOf("bonjour" to 5L, "bonjou" to 1_000_000L, "bonjours" to 900L))
        assertEquals("bonjour", d.correctionFor("bonjuor"))
    }

    @Test
    fun `l egalite de distance et de frequence reste ambigue`() {
        val d = Dictionary.withFrequencies(mapOf("chat" to 50L, "chah" to 50L))
        assertNull(d.correctionFor("cha"))
    }

    @Test
    fun `un mot personnel l emporte sur un mot du dictionnaire a distance egale`() {
        val d = Dictionary.withFrequencies(mapOf("chat" to 900L))
        assertEquals("chot", d.correctionFor("chit", personalWords = listOf("chot")))
    }

    @Test
    fun `withFrequencies garde la frequence la plus haute quand seule la casse differe`() {
        val d = Dictionary.withFrequencies(mapOf("Paris" to 10L, "paris" to 30L, "pari" to 20L))
        assertTrue(d.contains("PARIS"))
        assertEquals("pari", d.correctionFor("parj")) // 1 de "pari", 2 de "paris"
    }

    @Test
    fun `parseFrequencyLines lit mot et frequence et tolere les lignes sans frequence`() {
        val parsed = Dictionary.parseFrequencyLines(
            sequenceOf("de 8435682", "  je   8308698 ", "", "bonjour", "c'est 4184576", "mot-compose xyz"),
        )
        assertEquals(8435682L, parsed["de"])
        assertEquals(8308698L, parsed["je"])
        assertEquals(1L, parsed["bonjour"])
        assertEquals(4184576L, parsed["c'est"])
        assertEquals(1L, parsed["mot-compose xyz"])
        assertEquals(5, parsed.size)
    }

    @Test
    fun `une apostrophe oubliee est retablie meme si un mot voisin est plus frequent`() {
        val d = Dictionary.withFrequencies(mapOf("est" to 6_900_000L, "c'est" to 4_100_000L, "j'ai" to 2_400_000L))
        assertEquals("c'est", d.correctionFor("cest"))
        assertEquals("j'ai", d.correctionFor("jai"))
        assertEquals("C'est", d.correctionFor("Cest"))
    }

    @Test
    fun `un mot deja present dans le dictionnaire n est pas transforme en contraction`() {
        val d = Dictionary.withFrequencies(mapOf("dont" to 100L, "don't" to 900L))
        assertNull(d.correctionFor("dont"))
    }
}
