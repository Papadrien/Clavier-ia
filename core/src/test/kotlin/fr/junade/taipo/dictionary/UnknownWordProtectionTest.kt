package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Point 4 de docs/autocorrection-roadmap.md : protéger les mots justes inconnus du dictionnaire. */
class UnknownWordProtectionTest {

    private val dictionary = Dictionary.withFrequencies(
        mapOf("avec" to 50_000L, "bonjour" to 40_000L, "marine" to 9_000L, "chien" to 30_000L, "le" to 90_000L),
        InflectionRules.NONE,
        properNames = listOf("Adrien"),
        confidence = CorrectionConfidence.STANDARD,
        ngrams = NgramModel.parse(sequenceOf("le 1000", "le chien 300")),
    )

    @Test
    fun `un mot inconnu garde deux fois n'est plus corrige`() {
        val habits = CorrectionHabits()
        fun context() = HabitContext(habits, KeyboardLanguage.FR, null, null)
        assertEquals("marine", dictionary.correctionFor("marina", habits = context()))
        habits.recordUsage(KeyboardLanguage.FR, "marina")
        assertEquals("marine", dictionary.correctionFor("marina", habits = context()), "une seule fois peut être un accident")
        habits.recordUsage(KeyboardLanguage.FR, "marina")
        assertNull(dictionary.correctionFor("marina", habits = context()))
    }

    @Test
    fun `un mot inconnu a majuscule apres un autre mot n'est remplace que par un nom connu`() {
        // En début de phrase (pas de mot précédent), la majuscule ne dit rien : correction habituelle.
        assertEquals("Marine", dictionary.correctionFor("Marina"))
        // Après « avec » : sans doute un nom propre, gardé tel quel.
        assertNull(dictionary.correctionFor("Marina", previousWord = "avec"))
        // Un nom connu reste corrigé.
        assertEquals("Adrien", dictionary.correctionFor("Adrein", previousWord = "avec"))
        // Sans majuscule, rien ne change.
        assertEquals("marine", dictionary.correctionFor("marina", previousWord = "avec"))
    }

    @Test
    fun `un mot court a deux erreurs n'est corrige que si le contexte soutient la correction`() {
        // « cjoen » (5 lettres) est à deux erreurs de « chien » : sans contexte, c'est peut-être un mot inconnu.
        assertNull(dictionary.correctionFor("cjoen"))
        assertEquals("chien", dictionary.correctionFor("cjoen", previousWord = "le"))
    }

    @Test
    fun `une apostrophe initiale ou finale apres un mot connu est voulue`() {
        val italianLike = Dictionary.withFrequencies(mapOf("po" to 9_000L, "is" to 90_000L, "avonds" to 500L), InflectionRules.NONE)
        assertNull(italianLike.correctionFor("'s"), "néerlandais « 's avonds » : pas « is »")
        assertNull(italianLike.correctionFor("'t"))
        assertNull(italianLike.correctionFor("po'"), "italien « un po' »")
    }
}
