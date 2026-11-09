package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Autocorrection de l'espagnol (05/11/2026), sur un petit dictionnaire : politique d'accents propre à l'espagnol
 * ([AccentPolicy.SPANISH]) et touche Ñ. Les règles de contexte sont dans [SpanishConfusionsTest] ; le banc
 * (`AutocorrectBench --lang=es`) mesure l'ensemble sur le vrai dictionnaire.
 */
class SpanishAutocorrectionTest {

    private val frequencies = mapOf(
        "esta" to 1_000_000L, "está" to 2_300_000L, "tambien" to 17_000L, "también" to 480_000L, "aqui" to 65_000L, "aquí" to 1_600_000L,
        "papa" to 20_000L, "papá" to 400_000L, "seria" to 30_000L, "sería" to 300_000L, "español" to 9_500L, "mañana" to 280_000L,
        "año" to 500_000L, "ano" to 800L, "casa" to 400_000L, "casas" to 90_000L, "luz" to 60_000L, "luces" to 20_000L,
    )

    private fun dictionary(policy: AccentPolicy) = Dictionary.withFrequencies(
        frequencies, InflectionRules.SPANISH, 183L, KeyProximity.NONE,
        confidence = CorrectionConfidence.SPANISH, accentPolicy = policy,
    )

    private val spanish = dictionary(AccentPolicy.SPANISH)

    private fun fix(word: String, dictionary: Dictionary = spanish): String? =
        dictionary.correctionFor(word, personalWords = emptyList(), habits = null)

    @Test
    fun `un mot juste dont l'accent change le sens n'est pas remplace`() {
        // « esta » (celle-ci) est juste : 2,3 contre 1 ne suffit pas à le tenir pour un oubli d'accent.
        assertNull(fix("esta"))
        // « papa » (la pomme de terre), « seria » (l'adjectif) : mots justes listés, jamais corrigés pour leur accent.
        assertNull(fix("papa"))
        assertNull(fix("seria"))
    }

    @Test
    fun `un accent oublie presque sur est retabli`() {
        assertEquals("también", fix("tambien"))
        assertEquals("aquí", fix("aqui"))
        assertEquals("Aquí", fix("Aqui"))
    }

    @Test
    fun `le tilde du n est retabli`() {
        assertEquals("español", fix("espanol"))
        assertEquals("mañana", fix("manana"))
    }

    @Test
    fun `la politique du francais aurait remplace le mot juste`() {
        // Preuve de l'écart : au seuil de 2 du français, « esta » devient « está ».
        assertEquals("está", fix("esta", dictionary(AccentPolicy.FRENCH)))
    }

    @Test
    fun `les pluriels en s et en ces sont des formes justes`() {
        assertNull(fix("casas"))
        assertNull(fix("luces"))
    }

    @Test
    fun `la touche N tilde est une touche a part`() {
        val rows = listOf(
            "qwertyuiop".map { KeyProximity.KeyBox(it, 1f) },
            "asdfghjklñ".map { KeyProximity.KeyBox(it, 1f) },
            listOf(KeyProximity.KeyBox(null, 1.5f)) + "zxcvbnm".map { KeyProximity.KeyBox(it, 1f) } + listOf(KeyProximity.KeyBox(null, 1.5f)),
        )
        val proximity = KeyProximity.fromRows(rows)
        assertTrue(proximity.areNeighbors('ñ', 'l'))
        assertTrue(proximity.areNeighbors('ñ', 'p'))
        // Avant : « ñ » se repliait sur « n » et héritait de ses voisines (b, m, h, j).
        assertFalse(proximity.areNeighbors('ñ', 'b'))
        assertFalse(proximity.areNeighbors('ñ', 'n'))
    }
}
