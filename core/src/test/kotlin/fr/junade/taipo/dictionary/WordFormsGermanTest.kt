package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Repli et transcription allemands de [WordForms] (phase 2 du plan d'autocorrection allemande). */
class WordFormsGermanTest {

    @Test
    fun `le repli retire les umlauts et ecrit eszett en ss`() {
        assertEquals("fur", WordForms.foldAccents("für"))
        assertEquals("strasse", WordForms.foldAccents("straße"))
        assertEquals("grosse", WordForms.foldAccents("größe"))
    }

    @Test
    fun `le repli francais est inchange`() {
        assertEquals("duree", WordForms.foldAccents("durée"))
        assertEquals("coeur", WordForms.foldAccents("cœur"))
    }

    @Test
    fun `la transcription developpe les umlauts et eszett`() {
        assertEquals("fuer", WordForms.transliterateGerman("für"))
        assertEquals("strasse", WordForms.transliterateGerman("straße"))
        assertEquals("maedchen", WordForms.transliterateGerman("mädchen"))
        assertEquals("oel", WordForms.transliterateGerman("öl"))
    }

    @Test
    fun `la transcription d'un mot sans umlaut renvoie le meme mot`() {
        val word = "haus"
        assertSame(word, WordForms.transliterateGerman(word))
    }
}
