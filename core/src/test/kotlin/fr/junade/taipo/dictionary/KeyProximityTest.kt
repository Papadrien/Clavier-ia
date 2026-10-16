package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proximité des touches : voisinage calculé sur la géométrie des rangées, coût d'édition pondéré, et effet
 * sur le choix de la correction (départage à distance d'édition égale uniquement).
 */
class KeyProximityTest {

    private fun letters(text: String) = text.map { KeyProximity.KeyBox(it, 1f) }

    /** Rangées de l'AZERTY de l'application : Maj 1,4 + wxcvbn + apostrophe + Effacer 1,6 = 10 unités. */
    private val azerty = KeyProximity.fromRows(
        listOf(
            letters("azertyuiop"),
            letters("qsdfghjklm"),
            listOf(KeyProximity.KeyBox(null, 1.4f)) + letters("wxcvbn'") + listOf(KeyProximity.KeyBox(null, 1.6f)),
        ),
    )

    @Test
    fun `deux touches cote a cote sont voisines`() {
        assertTrue(azerty.areNeighbors('g', 'h'))
        assertTrue(azerty.areNeighbors('h', 'g'))
        assertTrue(azerty.areNeighbors('e', 'r'))
    }

    @Test
    fun `une touche est voisine de celles des rangees contigues`() {
        assertTrue(azerty.areNeighbors('g', 't'))
        assertTrue(azerty.areNeighbors('q', 'a'))
        assertTrue(azerty.areNeighbors('n', '\''))
    }

    @Test
    fun `des touches eloignees ou sur des rangees non contigues ne sont pas voisines`() {
        assertFalse(azerty.areNeighbors('g', 'p'))
        assertFalse(azerty.areNeighbors('a', 'w'))
        assertFalse(azerty.areNeighbors('a', 'a'))
    }

    @Test
    fun `la casse et les accents sont ignores`() {
        assertTrue(azerty.areNeighbors('G', 'h'))
        assertTrue(azerty.areNeighbors('é', 'r'))
    }

    @Test
    fun `sans proximite le cout est nul et rien n est voisin`() {
        assertFalse(KeyProximity.NONE.isEnabled)
        assertFalse(KeyProximity.NONE.areNeighbors('g', 'h'))
        assertEquals(0, KeyProximity.NONE.editCost("cgat", "chat"))
    }

    @Test
    fun `une touche voisine coute moins qu une touche quelconque`() {
        assertEquals(2, azerty.editCost("cgat", "chat"))
        assertEquals(4, azerty.editCost("cgat", "ceat"))
    }

    @Test
    fun `une lettre doublee ou effleuree coute moins qu une lettre en trop quelconque`() {
        assertEquals(2, azerty.editCost("chaat", "chat"))
        assertEquals(2, azerty.editCost("chgat", "chat"))
        assertEquals(4, azerty.editCost("chpat", "chat"))
    }

    @Test
    fun `une lettre manquante et une inversion`() {
        assertEquals(KeyProximity.DEFAULT_MISSING_LETTER_COST, azerty.editCost("cht", "chat"))
        assertEquals(2, azerty.editCost("teh", "the"))
    }

    @Test
    fun `le cout d une lettre manquante est reglable`() {
        val rows = listOf(letters("azertyuiop"), letters("qsdfghjklm"))
        assertEquals(2, KeyProximity.fromRows(rows, missingLetterCost = 2).editCost("cht", "chat"))
        assertEquals(4, KeyProximity.fromRows(rows, missingLetterCost = 4).editCost("cht", "chat"))
    }

    private val tie = mapOf("chat" to 1L, "ceat" to 1L)

    @Test
    fun `sans proximite deux candidats a egalite restent ambigus`() {
        assertNull(Dictionary.withFrequencies(tie).correctionFor("cgat"))
    }

    @Test
    fun `avec proximite la touche voisine departage les candidats`() {
        val dictionary = Dictionary.withFrequencies(tie, proximity = azerty)
        assertEquals("chat", dictionary.correctionFor("cgat"))
    }

    @Test
    fun `la proximite prime sur la frequence a distance egale`() {
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 1L, "ceat" to 5_000L), proximity = azerty)
        assertEquals("chat", dictionary.correctionFor("cgat"))
    }

    @Test
    fun `a proximite egale la frequence departage`() {
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 5L, "cfat" to 9L), proximity = azerty)
        assertEquals("cfat", dictionary.correctionFor("cgat"))
    }

    @Test
    fun `a proximite et frequence egales le resultat reste ambigu`() {
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 5L, "cfat" to 5L), proximity = azerty)
        assertNull(dictionary.correctionFor("cgat"))
    }

    @Test
    fun `la proximite ne change pas la distance maximale autorisee`() {
        // « cpat » -> « chat » : p et h ne sont pas voisines, mais la distance (1) reste la même : candidat unique.
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 1L), proximity = azerty)
        assertEquals("chat", dictionary.correctionFor("cpat"))
        // Distance 2 sur un mot de 4 lettres : toujours refusée, quelle que soit la proximité.
        assertNull(dictionary.correctionFor("cpqt"))
    }

    @Test
    fun `un mot personnel prime toujours sur la proximite`() {
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 1L), proximity = azerty)
        assertEquals("cpat", dictionary.correctionFor("cqat", personalWords = listOf("cpat")))
    }

    /** AZERTY complet : rangée de chiffres, virgule, point et barre d'espace (comme KeyProximityFactory). */
    private val azertyFull = KeyProximity.fromRows(
        listOf(
            letters("1234567890"),
            letters("azertyuiop"),
            letters("qsdfghjklm"),
            listOf(KeyProximity.KeyBox(null, 1.4f)) + letters("wxcvbn'") + listOf(KeyProximity.KeyBox(null, 1.6f)),
            listOf(
                KeyProximity.KeyBox(null, 1.4f), KeyProximity.KeyBox(',', 1f), KeyProximity.KeyBox(null, 1f),
                KeyProximity.KeyBox(KeyProximity.SPACE, 3.8f), KeyProximity.KeyBox('.', 1f), KeyProximity.KeyBox(null, 1.8f),
            ),
        ),
    )

    @Test
    fun `la rangee de chiffres est voisine de la rangee du haut`() {
        assertTrue(azertyFull.areNeighbors('1', 'a'))
        assertTrue(azertyFull.areNeighbors('3', 'e'))
        assertTrue(azertyFull.areNeighbors('3', '4'))
        assertFalse(azertyFull.areNeighbors('1', 'p'))
        assertFalse(azertyFull.areNeighbors('1', 'q'))
    }

    @Test
    fun `la barre d espace est voisine des lettres du bas et de la ponctuation`() {
        assertTrue(azertyFull.isNextToSpace('v'))
        assertTrue(azertyFull.isNextToSpace('b'))
        assertTrue(azertyFull.isNextToSpace('n'))
        assertTrue(azertyFull.areNeighbors(',', 'x'))
        assertFalse(azertyFull.isNextToSpace('a'))
        assertFalse(azertyFull.isNextToSpace('p'))
    }

    @Test
    fun `une lettre voisine de l espace en fin ou debut de mot est une lettre en trop probable`() {
        assertEquals(2, azertyFull.editCost("bonjourn", "bonjour"))
        assertEquals(4, azertyFull.editCost("bonjourp", "bonjour"))
        // Au milieu du mot, le voisinage de l'espace ne compte pas.
        assertEquals(4, azertyFull.editCost("bonvjour", "bonjour"))
    }

    @Test
    fun `un chiffre tape a la place d une lettre voisine est corrige`() {
        val dictionary = Dictionary.withFrequencies(mapOf("merci" to 10L), proximity = azertyFull)
        assertEquals("merci", dictionary.correctionFor("m3rci"))
        assertEquals("Merci", dictionary.correctionFor("M3rci"))
    }

    @Test
    fun `un mot avec chiffre reste intact sauf glissement d un seul chiffre sur une touche voisine`() {
        val dictionary = Dictionary.withFrequencies(mapOf("merci" to 10L, "mars" to 10L), proximity = azertyFull)
        // chiffre trop court, deux chiffres, chiffre non voisin de la lettre attendue, acronyme.
        assertNull(dictionary.correctionFor("m3r"))
        assertNull(dictionary.correctionFor("m33ci"))
        assertNull(dictionary.correctionFor("m9rci"))
        assertNull(dictionary.correctionFor("MERC1"))
        // Sans proximité, comportement inchangé.
        assertNull(Dictionary.withFrequencies(mapOf("merci" to 10L)).correctionFor("m3rci"))
    }
}
