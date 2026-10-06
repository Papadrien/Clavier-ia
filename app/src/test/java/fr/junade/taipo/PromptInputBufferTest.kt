package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptInputBufferTest {

    private val grin = "\uD83D\uDE00" // 😀
    private val wave = "\uD83D\uDC4B\uD83C\uDFFD" // 👋🏽
    private val womanTech = "\uD83D\uDC69\u200D\uD83D\uDCBB" // 👩‍💻
    private val flag = "\uD83C\uDDEB\uD83C\uDDF7" // 🇫🇷

    private fun buffer(text: String): PromptInputBuffer = PromptInputBuffer().apply { insert(text) }

    @Test
    fun `un tampon neuf est vide`() {
        val buffer = PromptInputBuffer()
        assertEquals("", buffer.text)
        assertEquals(0, buffer.cursor)
        assertTrue(buffer.isBlank)
    }

    @Test
    fun `insert place le curseur apres le texte insere`() {
        val buffer = PromptInputBuffer()
        buffer.insert("bon")
        buffer.insert("jour")
        assertEquals("bonjour", buffer.text)
        assertEquals(7, buffer.cursor)
        assertFalse(buffer.isBlank)
    }

    @Test
    fun `insert au milieu du texte`() {
        val buffer = buffer("bonjour")
        buffer.moveCursor(-4)
        buffer.insert("-")
        assertEquals("bon-jour", buffer.text)
        assertEquals(4, buffer.cursor)
        assertEquals("bon-", buffer.textBeforeCursor)
        assertEquals("jour", buffer.textAfterCursor)
    }

    @Test
    fun `un texte vide inserer ne change rien`() {
        val buffer = buffer("a")
        buffer.insert("")
        assertEquals("a", buffer.text)
        assertEquals(1, buffer.cursor)
    }

    @Test
    fun `backspace supprime la derniere lettre`() {
        val buffer = buffer("abc")
        assertTrue(buffer.backspace())
        assertEquals("ab", buffer.text)
        assertEquals(2, buffer.cursor)
    }

    @Test
    fun `backspace au milieu garde le texte apres le curseur`() {
        val buffer = buffer("abcd")
        buffer.moveCursor(-2)
        assertTrue(buffer.backspace())
        assertEquals("acd", buffer.text)
        assertEquals(1, buffer.cursor)
    }

    @Test
    fun `backspace sur un tampon vide ou au debut renvoie faux`() {
        assertFalse(PromptInputBuffer().backspace())
        val buffer = buffer("ab")
        buffer.moveCursor(-2)
        assertFalse(buffer.backspace())
        assertEquals("ab", buffer.text)
    }

    @Test
    fun `backspace supprime un emoji en entier`() {
        for (emoji in listOf(grin, wave, womanTech, flag)) {
            val buffer = buffer("a$emoji")
            assertTrue(buffer.backspace())
            assertEquals("a", buffer.text)
            assertEquals(1, buffer.cursor)
        }
    }

    @Test
    fun `moveCursor est borne au texte`() {
        val buffer = buffer("abc")
        buffer.moveCursor(10)
        assertEquals(3, buffer.cursor)
        buffer.moveCursor(-10)
        assertEquals(0, buffer.cursor)
    }

    @Test
    fun `moveCursor saute un emoji simple en un pas`() {
        val buffer = buffer("a${grin}b")
        buffer.moveCursor(-3)
        assertEquals(0, buffer.cursor)
        buffer.moveCursor(1)
        assertEquals(1, buffer.cursor)
        buffer.moveCursor(1)
        assertEquals(3, buffer.cursor)
        buffer.moveCursor(1)
        assertEquals(4, buffer.cursor)
    }

    @Test
    fun `moveCursor saute un emoji a teinte de peau en un pas`() {
        val buffer = buffer("a${wave}b")
        buffer.moveCursor(-3)
        buffer.moveCursor(1)
        assertEquals(1, buffer.cursor)
        buffer.moveCursor(1)
        assertEquals(5, buffer.cursor)
        buffer.moveCursor(-1)
        assertEquals(1, buffer.cursor)
    }

    @Test
    fun `moveCursor saute une sequence ZWJ en un pas`() {
        val buffer = buffer("x${womanTech}y")
        buffer.moveCursor(-3)
        buffer.moveCursor(1)
        assertEquals(1, buffer.cursor)
        buffer.moveCursor(1)
        assertEquals(6, buffer.cursor)
        buffer.moveCursor(-1)
        assertEquals(1, buffer.cursor)
    }

    @Test
    fun `moveCursor saute un drapeau en un pas et deux drapeaux en deux`() {
        val buffer = buffer(flag + flag)
        buffer.moveCursor(-4)
        assertEquals(0, buffer.cursor)
        buffer.moveCursor(1)
        assertEquals(4, buffer.cursor)
        buffer.moveCursor(1)
        assertEquals(8, buffer.cursor)
    }

    @Test
    fun `backspace apres un deplacement du curseur supprime l emoji a gauche`() {
        val buffer = buffer("a${womanTech}b")
        buffer.moveCursor(-1)
        assertTrue(buffer.backspace())
        assertEquals("ab", buffer.text)
        assertEquals(1, buffer.cursor)
    }

    @Test
    fun `replaceAll remplace le texte et place le curseur a la fin`() {
        val buffer = buffer("ancien")
        buffer.moveCursor(-3)
        buffer.replaceAll("nouveau")
        assertEquals("nouveau", buffer.text)
        assertEquals(7, buffer.cursor)
    }

    @Test
    fun `clear vide le tampon`() {
        val buffer = buffer("abc")
        buffer.clear()
        assertEquals("", buffer.text)
        assertEquals(0, buffer.cursor)
        assertTrue(buffer.isBlank)
    }

    @Test
    fun `un tampon de espaces est considere vide`() {
        assertTrue(buffer("  \n ").isBlank)
    }

    @Test
    fun `deleteBefore supprime des caracteres avant le curseur`() {
        val buffer = buffer("bonjour tout le monde")
        assertEquals(5, buffer.deleteBefore(5))
        assertEquals("bonjour tout le ", buffer.text)
        assertEquals(16, buffer.cursor)
    }

    @Test
    fun `deleteBefore garde le texte apres le curseur`() {
        val buffer = buffer("un deux trois")
        buffer.moveCursor(-5) // devant « trois »
        assertEquals(5, buffer.deleteBefore(5))
        assertEquals("un trois", buffer.text)
        assertEquals(3, buffer.cursor)
    }

    @Test
    fun `deleteBefore est borne au curseur`() {
        val buffer = buffer("abc")
        assertEquals(3, buffer.deleteBefore(10))
        assertEquals("", buffer.text)
        assertEquals(0, buffer.cursor)
        assertEquals(0, buffer.deleteBefore(1))
    }

    @Test
    fun `deleteBefore avec zero ou un nombre negatif ne change rien`() {
        val buffer = buffer("abc")
        assertEquals(0, buffer.deleteBefore(0))
        assertEquals(0, buffer.deleteBefore(-2))
        assertEquals("abc", buffer.text)
        assertEquals(3, buffer.cursor)
    }

    @Test
    fun `setCursor place le curseur au milieu du texte`() {
        val buffer = buffer("abcd")
        assertTrue(buffer.setCursor(2))
        assertEquals(2, buffer.cursor)
        buffer.insert("X")
        assertEquals("abXcd", buffer.text)
    }

    @Test
    fun `setCursor sans changement renvoie faux et borne la position au texte`() {
        val buffer = buffer("abc")
        assertFalse(buffer.setCursor(3))
        assertFalse(buffer.setCursor(99))
        assertTrue(buffer.setCursor(-5))
        assertEquals(0, buffer.cursor)
    }

    @Test
    fun `setCursor ne coupe ni une paire de substitution ni un emoji compose`() {
        val buffer = buffer("a$grin" + "b$womanTech" + "c")
        // Au milieu de la paire de substitution de 😀 (index 2) : on se ramène à une limite de caractère.
        buffer.setCursor(2)
        assertTrue(buffer.cursor == 1 || buffer.cursor == 3)
        // Au milieu de la séquence ZWJ 👩‍💻 : jamais à l'intérieur.
        val start = 1 + grin.length + 1
        buffer.setCursor(start + 2)
        assertTrue(buffer.cursor == start || buffer.cursor == start + womanTech.length)
    }
}
