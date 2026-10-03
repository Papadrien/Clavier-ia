package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldContextTest {

    @Test
    fun `un champ absent ou vide ne donne rien a joindre`() {
        assertNull(FieldContext.prepare(null))
        assertNull(FieldContext.prepare(""))
        assertNull(FieldContext.prepare("  \n\t "))
    }

    @Test
    fun `un texte court est garde tel quel sans espaces de bord`() {
        assertEquals("Bonjour Léa,\nà demain", FieldContext.prepare("  Bonjour Léa,\nà demain \n"))
    }

    @Test
    fun `un texte a la limite exacte n est pas tronque`() {
        val text = "a".repeat(10)
        assertEquals(text, FieldContext.prepare(text, maxChars = 10))
    }

    @Test
    fun `un texte trop long garde la fin et signale la coupe`() {
        val text = "0123456789"
        assertEquals("[…] 56789", FieldContext.prepare(text, maxChars = 5))
    }

    @Test
    fun `la coupe retire les espaces qui suivent le marqueur`() {
        // Les 5 derniers caractères sont « 5678 » précédés d'une espace : elle est retirée.
        assertEquals("[…] 5678", FieldContext.prepare("01234 5678", maxChars = 5))
    }

    @Test
    fun `la coupe ne separe pas un emoji`() {
        val emoji = "\uD83D\uDE00" // 2 caractères UTF-16
        val text = "abc$emoji" + "def"
        // maxChars = 4 couperait entre les deux moitiés de l'emoji (…\uDE00def) : on avance d'un caractère.
        val result = FieldContext.prepare(text, maxChars = 4)!!
        assertEquals("[…] def", result)
        assertFalse(result.contains('\uDE00'))
    }

    @Test
    fun `une coupe qui ne laisserait que la moitie d un emoji ne donne rien`() {
        assertNull(FieldContext.prepare("abc\uD83D\uDE00", maxChars = 1))
    }

    @Test
    fun `une limite nulle ou negative est refusee`() {
        assertThrows(IllegalArgumentException::class.java) { FieldContext.prepare("a", maxChars = 0) }
    }

    @Test
    fun `compose sans contexte renvoie le prompt seul`() {
        assertEquals("Écris un mail", FieldContext.compose("Texte du champ :", null, "Écris un mail"))
    }

    @Test
    fun `compose place le contexte entre guillemets triples avant le prompt`() {
        val composed = FieldContext.compose("Texte du champ :", "Salut Paul", "Rends-le plus formel")
        assertEquals("Texte du champ :\n\"\"\"\nSalut Paul\n\"\"\"\n\nRends-le plus formel", composed)
        assertTrue(composed.endsWith("Rends-le plus formel"))
    }
}
