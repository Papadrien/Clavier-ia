package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** Modèle de la lettre suivante (zones de frappe adaptatives, étape 1) : probabilités d'après le début du mot. */
class NextLetterModelTest {

    private val delta = 1e-9

    private fun model(vararg entries: Pair<String, Long>, exponent: Double = 1.0) =
        NextLetterModel.fromFrequencies(mapOf(*entries), exponent)

    private fun NextLetterModel.after(prefix: String): NextLetterModel.Probabilities =
        probabilitiesAfter(prefix) ?: throw AssertionError("Préfixe « $prefix » attendu connu")

    private fun NextLetterModel.Probabilities.total(): Double = next.values.sum() + endOfWord

    @Test
    fun `la lettre la plus frequente apres le prefixe domine`() {
        // Exemple d'iOS : après « tim », le « e » (time) est de loin le plus probable.
        val m = model("time" to 80L, "timber" to 10L, "timid" to 10L, "tim" to 5L)
        val p = m.after("tim")
        assertEquals(80.0 / 105.0, p.of('e'), delta)
        assertEquals(10.0 / 105.0, p.of('b'), delta)
        assertEquals(10.0 / 105.0, p.of('i'), delta)
        assertEquals(5.0 / 105.0, p.endOfWord, delta)
        assertEquals(0.0, p.of('z'), 0.0)
        assertEquals(4, p.wordCount)
    }

    @Test
    fun `les probabilites totalisent un`() {
        val m = model("le" to 100L, "la" to 90L, "les" to 50L, "l'homme" to 7L, "l" to 3L)
        listOf("", "l", "le", "l'").forEach { assertEquals(1.0, m.after(it).total(), delta, "préfixe « $it »") }
    }

    @Test
    fun `un prefixe vide donne la repartition des premieres lettres`() {
        val m = model("avion" to 60L, "arbre" to 20L, "bleu" to 20L)
        val p = m.after("")
        assertEquals(0.8, p.of('a'), delta)
        assertEquals(0.2, p.of('b'), delta)
        assertEquals(0.0, p.endOfWord, 0.0)
    }

    @Test
    fun `un prefixe inconnu n a pas de prediction`() {
        val m = model("bonjour" to 10L, "bonsoir" to 5L)
        assertNull(m.probabilitiesAfter("bonx"))
        assertNull(m.probabilitiesAfter("zzz"))
        // Un mot plus long que tous ceux du dictionnaire qui commencent pareil.
        assertNull(m.probabilitiesAfter("bonjours"))
    }

    @Test
    fun `un mot complet n a que la fin de mot`() {
        val p = model("bonjour" to 10L, "bonsoir" to 5L).after("bonjour")
        assertEquals(1.0, p.endOfWord, delta)
        assertTrue(p.next.isEmpty())
    }

    @Test
    fun `les accents et la casse sont replies sur la touche`() {
        val m = model("été" to 50L, "ete" to 10L, "étendre" to 5L)
        // « é » compte pour la touche « e » ; le préfixe tapé avec majuscule ou accent donne le même résultat.
        val lower = m.after("et")
        val typed = m.after("Ét")
        assertEquals(lower.of('e'), typed.of('e'), delta)
        assertEquals(1.0, lower.total(), delta)
        // « été » et « ete » se replient sur le même mot : tous deux comptent pour la fin de mot.
        assertEquals(60.0 / 65.0, m.after("ete").endOfWord, delta)
        assertEquals(5.0 / 65.0, m.after("ete").of('n'), delta)
        assertEquals(1.0, m.after("e").of('t'), delta)
    }

    @Test
    fun `l apostrophe et le trait d union sont des caracteres suivants comme les lettres`() {
        val m = model("l'homme" to 30L, "la" to 60L, "le" to 8L, "l-a" to 2L)
        val p = m.after("l")
        assertEquals(0.3, p.of('\''), delta)
        assertEquals(0.02, p.of('-'), delta)
        assertEquals(0.6, p.of('a'), delta)
        assertEquals(0.08, p.of('e'), delta)
        assertEquals(1.0, p.total(), delta)
        // Après l'apostrophe (droite ou typographique), la lettre suivante est connue.
        assertEquals(1.0, m.after("l'").of('h'), delta)
        assertEquals(1.0, m.after("l\u2019").of('h'), delta)
    }

    @Test
    fun `un exposant plus petit attenue la domination d un mot tres frequent`() {
        val entries = arrayOf("de" to 10_000L, "da" to 100L)
        val raw = model(*entries).after("d")
        val damped = model(*entries, exponent = 0.5).after("d")
        assertEquals(10_000.0 / 10_100.0, raw.of('e'), delta)
        assertEquals(100.0 / 110.0, damped.of('e'), delta) // racines : 100 contre 10
        assertTrue(damped.of('a') > raw.of('a'))
    }

    @Test
    fun `une frequence nulle compte comme un`() {
        val p = model("ab" to 0L, "ac" to 0L).after("a")
        assertEquals(0.5, p.of('b'), delta)
        assertEquals(0.5, p.of('c'), delta)
    }

    @Test
    fun `un exposant ou des tableaux invalides sont refuses`() {
        assertThrows(IllegalArgumentException::class.java) { NextLetterModel.fromFrequencies(mapOf("a" to 1L), 0.0) }
        assertThrows(IllegalArgumentException::class.java) { NextLetterModel.fromCandidates(arrayOf("a", "b"), longArrayOf(1L)) }
    }

    @Test
    fun `un dictionnaire vide ne predit rien`() {
        assertNull(NextLetterModel.fromFrequencies(emptyMap()).probabilitiesAfter(""))
    }

    /** Référence lente (parcours complet) : le modèle trié doit donner exactement les mêmes probabilités. */
    @Test
    fun `le modele est identique a un calcul par parcours complet`() {
        val random = Random(42)
        val alphabet = "abcdé'-"
        val entries = HashMap<String, Long>()
        repeat(3_000) {
            val word = String(CharArray(1 + random.nextInt(6)) { alphabet[random.nextInt(alphabet.length)] })
            entries[word] = 1L + random.nextInt(1_000)
        }
        val m = NextLetterModel.fromFrequencies(entries)
        val folded = entries.map { (word, frequency) -> NextLetterModel.normalize(word) to frequency }

        val prefixes = HashSet<String>().apply {
            add("")
            entries.keys.forEach { word -> for (n in 1..word.length) add(word.substring(0, n)) }
            add("dd'-a") // préfixe absent
        }
        for (prefix in prefixes) {
            val p = NextLetterModel.normalize(prefix)
            val matches = folded.filter { it.first.startsWith(p) }
            val actual = m.probabilitiesAfter(prefix)
            if (matches.isEmpty()) {
                assertNull(actual, "préfixe « $prefix »")
                continue
            }
            assertNotNull(actual, "préfixe « $prefix »")
            val total = matches.sumOf { it.second }.toDouble()
            assertEquals(matches.size, actual!!.wordCount, "nombre de mots, préfixe « $prefix »")
            assertEquals(matches.filter { it.first.length == p.length }.sumOf { it.second } / total, actual.endOfWord, delta, "fin, « $prefix »")
            val longer = matches.filter { it.first.length > p.length }
            for (char in "abcde'-z") {
                val expected = longer.filter { it.first[p.length] == char }.sumOf { it.second } / total
                assertEquals(expected, actual.of(char), delta, "caractère $char, « $prefix »")
            }
            assertEquals(longer.map { it.first[p.length] }.toSet(), actual.next.keys, "caractères suivants, « $prefix »")
        }
    }

    @Test
    fun `le dictionnaire expose le modele de ses candidats seulement`() {
        // Seuil de candidats à 10 : « tard » (fréquence 3) reste un mot valide mais n'entre pas dans le modèle.
        val dictionary = Dictionary.withFrequencies(
            mapOf("tarte" to 40L, "tarif" to 20L, "tard" to 3L),
            candidateMinFrequency = 10L,
        )
        val p = dictionary.nextLetterModel.after("tar")
        assertEquals(40.0 / 60.0, p.of('t'), delta)
        assertEquals(20.0 / 60.0, p.of('i'), delta)
        assertEquals(0.0, p.of('d'), 0.0)
        // Le modèle est construit une seule fois.
        assertTrue(dictionary.nextLetterModel === dictionary.nextLetterModel)
    }
}
