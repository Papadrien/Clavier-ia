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
}
