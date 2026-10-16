package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Proximité construite à partir des dispositions réelles du clavier (AZERTY / QWERTY). */
class KeyProximityFactoryTest {

    private val fr = KeyProximityFactory.forLanguage(KeyboardLanguage.FR)
    private val en = KeyProximityFactory.forLanguage(KeyboardLanguage.EN)

    @Test
    fun `AZERTY - voisines horizontales et verticales`() {
        assertTrue(fr.areNeighbors('g', 'h'))
        assertTrue(fr.areNeighbors('a', 'z'))
        assertTrue(fr.areNeighbors('a', 'q'))
        assertTrue(fr.areNeighbors('e', 'r'))
    }

    @Test
    fun `AZERTY - l apostrophe est voisine du N`() {
        assertTrue(fr.areNeighbors('n', '\''))
    }

    @Test
    fun `AZERTY - touches eloignees`() {
        assertFalse(fr.areNeighbors('a', 'p'))
        assertFalse(fr.areNeighbors('q', 'm'))
    }

    @Test
    fun `QWERTY - voisines`() {
        assertTrue(en.areNeighbors('q', 'w'))
        assertTrue(en.areNeighbors('a', 'q'))
        assertTrue(en.areNeighbors('s', 'z'))
    }

    @Test
    fun `la disposition differe entre les langues`() {
        // Sur l'AZERTY a est à côté de z, sur le QWERTY à côté de q seulement dans la rangée du haut.
        assertTrue(fr.areNeighbors('a', 'z'))
        assertFalse(en.areNeighbors('a', 'e'))
    }

    @Test
    fun `la proximite est active pour les deux langues`() {
        assertTrue(fr.isEnabled)
        assertTrue(en.isEnabled)
    }

    @Test
    fun `la rangee de chiffres est voisine des lettres du haut`() {
        assertTrue(fr.areNeighbors('1', 'a'))
        assertTrue(fr.areNeighbors('3', 'e'))
        assertTrue(en.areNeighbors('1', 'q'))
        assertFalse(fr.areNeighbors('1', 'p'))
    }

    @Test
    fun `la barre d espace est voisine des lettres du bas`() {
        listOf(fr, en).forEach {
            assertTrue(it.isNextToSpace('v'))
            assertTrue(it.isNextToSpace('b'))
            assertFalse(it.isNextToSpace('a'))
        }
    }

    @Test
    fun `la virgule et le point sont voisins des lettres du bas`() {
        assertTrue(fr.areNeighbors(',', 'x'))
        assertTrue(fr.areNeighbors('.', 'n') || fr.areNeighbors('.', '\''))
    }
}
