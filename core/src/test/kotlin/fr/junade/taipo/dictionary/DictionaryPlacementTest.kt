package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.WordSuggestion.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Curseur posé sur un mot déjà écrit : trois propositions classées par le moteur d'autocorrection. */
class DictionaryPlacementTest {

    // Extrait des vraies fréquences de fr.txt.
    private val fr = Dictionary.withFrequencies(
        mapOf(
            "peu" to 1_200_000L, "peut" to 800_000L, "peux" to 594_578L, "peur" to 400_000L, "peuple" to 50_000L,
            "bon" to 900_000L, "bonne" to 300_000L, "bonjour" to 250_000L, "non" to 3_000_000L,
            "écoute" to 81_813L, "ecoute" to 38_316L, "écouté" to 40_000L, "écoutez" to 40_000L,
            "où" to 643_564L, "ou" to 497_358L, "oui" to 2_000_000L,
            "maison" to 300_000L, "maisons" to 60_000L, "il" to 5_000_000L, "ils" to 2_000_000L,
        ),
        InflectionRules.FRENCH,
    )

    private fun texts(slots: List<WordSuggestion?>) = slots.map { it?.text }

    @Test
    fun `un mot valide propose des alternatives classees par frequence`() {
        val slots = fr.placementSlotsFor("peu")
        assertEquals(listOf("peut", "peux", "peur"), texts(slots))
        assertTrue(slots.all { it?.kind == Kind.COMPLETION })
    }

    @Test
    fun `un mot sans alternative laisse des emplacements vides`() {
        val slots = fr.placementSlotsFor("maison")
        assertEquals("maisons", slots[0]?.text)
        assertNull(slots[1])
        assertNull(slots[2])
    }

    @Test
    fun `un mot qui va etre corrige garde le mot tape a gauche puis la correction`() {
        val slots = fr.placementSlotsFor("oeux")
        assertEquals(Kind.TYPED, slots[0]?.kind)
        assertEquals("oeux", slots[0]?.text)
        assertEquals(Kind.AUTOCORRECTION, slots[1]?.kind)
        assertEquals("peux", slots[1]?.text)
    }

    @Test
    fun `un mot deja corrige propose a gauche sa forme d'origine`() {
        val slots = fr.placementSlotsFor("écoute", previousForm = "ecoute")
        assertEquals(WordSuggestion("ecoute", Kind.TYPED), slots[0])
        assertEquals(listOf("écouté", "écoutez"), texts(slots).drop(1))
    }

    @Test
    fun `la forme d'origine n'est pas repetee dans les alternatives`() {
        val slots = fr.placementSlotsFor("écoute", previousForm = "ecoute")
        assertEquals(1, texts(slots).count { it == "ecoute" })
    }

    @Test
    fun `une forme d'origine identique au mot actuel est ignoree`() {
        val slots = fr.placementSlotsFor("peu", previousForm = "PEU")
        assertEquals(Kind.COMPLETION, slots[0]?.kind)
    }

    @Test
    fun `la forme accentuee du mot complet passe en premier`() {
        assertEquals("où", fr.placementSlotsFor("ou")[0]?.text)
    }

    @Test
    fun `la majuscule du mot est reprise`() {
        assertEquals("Peut", fr.placementSlotsFor("Peu")[0]?.text)
    }

    @Test
    fun `un mot sans lettre ne propose rien`() {
        assertEquals(listOf(null, null, null), fr.placementSlotsFor("123"))
    }

    @Test
    fun `un mot rare n'est pas propose comme voisin d'un mot bien plus courant`() {
        // « il » (5 000 000) est à une lettre de « ils » mais « ils » est plus rare : il reste proposé ; l'inverse n'est
        // pas vrai pour un voisin 20 fois plus rare que le mot tapé.
        val rare = Dictionary.withFrequencies(mapOf("chats" to 1_000L, "chat" to 500_000L, "chut" to 100L))
        val slots = rare.placementSlotsFor("chat")
        assertTrue("chut" !in texts(slots))
        assertEquals("chats", slots[0]?.text)
    }
}
