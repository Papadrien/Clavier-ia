package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InsertSpacingTest {

    @Test
    fun `une espace est ajoutee apres un mot ou un emoji`() {
        assertEquals(" La mer.", InsertSpacing.around('t', "La mer.", null))
        val grape = "🍇"
        assertEquals(" La mer.", InsertSpacing.around(grape.last(), "La mer.", null))
    }

    @Test
    fun `rien n'est ajoute en debut de champ ni apres un blanc`() {
        assertEquals("La mer.", InsertSpacing.around(null, "La mer.", null))
        assertEquals("La mer.", InsertSpacing.around(' ', "La mer.", null))
        assertEquals("La mer.", InsertSpacing.around('\n', "La mer.", null))
    }

    @Test
    fun `rien n'est ajoute apres une ouverture ni devant une ponctuation`() {
        assertEquals("La mer", InsertSpacing.around('(', "La mer", null))
        assertEquals("La mer", InsertSpacing.around('«', "La mer", null))
        assertEquals("La mer", InsertSpacing.around('\'', "La mer", null))
        assertEquals(", la mer", InsertSpacing.around('t', ", la mer", null))
    }

    @Test
    fun `une espace est ajoutee devant un mot qui suit`() {
        assertEquals("La mer ", InsertSpacing.around(null, "La mer", 'e'))
        assertEquals("La mer", InsertSpacing.around(null, "La mer", ' '))
        assertEquals("La mer", InsertSpacing.around(null, "La mer", '.'))
    }

    @Test
    fun `un blanc deja au bord du texte n'est pas double`() {
        assertEquals(" À bientôt.", InsertSpacing.around('.', " À bientôt.", null))
        assertEquals("La mer ", InsertSpacing.around(null, "La mer ", 'e'))
    }

    @Test
    fun `un texte vide reste vide`() {
        assertEquals("", InsertSpacing.around('a', "", 'b'))
    }
}
