package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Prénoms : majuscule, correction des fautes et respect du dictionnaire personnel. */
class ProperNamesTest {

    private val dictionary = Dictionary.withFrequencies(
        mapOf("bonjour" to 10_000L, "adrien" to 466L, "eric" to 300L, "pierre" to 5_000L, "marie" to 9_000L),
        properNames = listOf("Adrien", "Éric", "Mathilde", "Marie"),
    )

    @Test
    fun `un prenom tape en minuscules prend sa majuscule`() {
        assertEquals("Adrien", dictionary.correctionFor("adrien"))
        assertEquals("Marie", dictionary.correctionFor("marie"))
    }

    @Test
    fun `un prenom deja bien ecrit n est pas touche`() {
        assertNull(dictionary.correctionFor("Adrien"))
        assertNull(dictionary.correctionFor("ADRIEN"))
    }

    @Test
    fun `une faute dans un prenom est corrigee avec sa majuscule`() {
        assertEquals("Adrien", dictionary.correctionFor("adrein"))
        assertEquals("Adrien", dictionary.correctionFor("Adrein"))
    }

    @Test
    fun `un prenom absent des listes est quand meme corrige`() {
        assertEquals("Mathilde", dictionary.correctionFor("mathide"))
    }

    @Test
    fun `les accents d un prenom sont retablis sur un mot tape en minuscules`() {
        assertEquals("Éric", dictionary.correctionFor("eric"))
        // Tapé avec une majuscule, le mot garde sa graphie (« Eric » est aussi un prénom courant en français).
        assertNull(dictionary.correctionFor("Eric"))
    }

    @Test
    fun `un mot courant absent de la liste de prenoms reste intact`() {
        assertNull(dictionary.correctionFor("pierre"))
        assertNull(dictionary.correctionFor("bonjour"))
    }

    @Test
    fun `le dictionnaire personnel prime sur la majuscule`() {
        assertNull(dictionary.correctionFor("adrien", personalWords = listOf("adrien")))
        assertNull(dictionary.correctionFor("Adrien", personalWords = listOf("Adrien")))
    }

    @Test
    fun `apres une elision le prenom prend sa majuscule`() {
        assertEquals("d'Adrien", dictionary.correctionFor("d'adrien"))
        assertEquals("l'Adrien", dictionary.correctionFor("l'adrien"))
    }

    @Test
    fun `sans liste de prenoms le comportement est inchange`() {
        val plain = Dictionary.withFrequencies(mapOf("adrien" to 466L, "bonjour" to 10_000L))
        assertNull(plain.correctionFor("adrien"))
    }
}
