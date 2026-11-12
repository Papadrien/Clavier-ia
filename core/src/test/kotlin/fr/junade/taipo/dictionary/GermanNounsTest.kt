package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Allemand, phase 3 : la liste de noms (`dictionaries/names/de.txt`) met la majuscule aux noms communs et aux prénoms, sans réglage ni contexte. */
class GermanNounsTest {

    private val dictionary = Dictionary.withFrequencies(
        mapOf(
            "haus" to 70_000L,
            "hohe" to 5_000L,
            "höhe" to 4_000L,
            "ich" to 5_000_000L,
            "sehe" to 90_000L,
            "das" to 3_000_000L,
            "essen" to 67_000L,
            "gehen" to 300_000L,
        ),
        candidateMinFrequency = 41L,
        properNames = listOf("Haus", "Höhe", "Jürgen", "Krankenhaus", "Straße"),
        accentPolicy = AccentPolicy.GERMAN,
    )

    @Test
    fun `un nom tape en minuscules prend sa majuscule`() {
        assertEquals("Haus", dictionary.correctionFor("haus"))
    }

    @Test
    fun `une faute dans un nom est corrigee avec sa majuscule`() {
        assertEquals("Haus", dictionary.correctionFor("hauss"))
    }

    @Test
    fun `un nom absent des listes est quand meme corrige`() {
        // « krankenhaus » n'est pas dans les listes de ce test : il vient de la liste de noms (index par longueur).
        assertEquals("Krankenhaus", dictionary.correctionFor("krankenhauss"))
    }

    @Test
    fun `un nom deja bien ecrit ou en capitales n est pas touche`() {
        assertNull(dictionary.correctionFor("Haus"))
        assertNull(dictionary.correctionFor("HAUS"))
    }

    @Test
    fun `un mot absent de la liste de noms reste intact`() {
        // « essen » (verbe et nom) n'est pas dans la liste : jamais de majuscule automatique.
        assertNull(dictionary.correctionFor("essen"))
        assertNull(dictionary.correctionFor("gehen"))
    }

    @Test
    fun `un mot valide n est pas remplace par un nom pour son seul umlaut`() {
        // « hohe » (adjectif) est dans les listes : la liste de noms contient « Höhe » mais ne doit pas le remplacer.
        assertNull(dictionary.correctionFor("hohe"))
        assertEquals("Höhe", dictionary.correctionFor("höhe"))
    }

    @Test
    fun `un prenom sans umlaut retrouve son umlaut et sa majuscule`() {
        assertEquals("Jürgen", dictionary.correctionFor("jurgen"))
    }

    @Test
    fun `le dictionnaire personnel prime sur la majuscule`() {
        assertNull(dictionary.correctionFor("haus", personalWords = listOf("haus")))
    }
}
