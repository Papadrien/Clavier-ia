package fr.junade.taipo.emoji

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmojiTextTest {

    @Test
    fun `texte vide`() {
        assertEquals(0, EmojiText.lastClusterLength(""))
    }

    @Test
    fun `une lettre ou un signe se supprime seul`() {
        assertEquals(1, EmojiText.lastClusterLength("a"))
        assertEquals(1, EmojiText.lastClusterLength("bonjour"))
        assertEquals(1, EmojiText.lastClusterLength("fin."))
        assertEquals(1, EmojiText.lastClusterLength("ligne\n"))
    }

    @Test
    fun `un emoji simple occupe deux caracteres`() {
        assertEquals(2, EmojiText.lastClusterLength("\uD83D\uDE00")) // 😀
        assertEquals(2, EmojiText.lastClusterLength("salut \uD83D\uDE00"))
    }

    @Test
    fun `un emoji a presentation texte suivi de FE0F part en entier`() {
        assertEquals(2, EmojiText.lastClusterLength("a\u2764\uFE0F")) // ❤️
    }

    @Test
    fun `une teinte de peau fait partie de l emoji`() {
        assertEquals(4, EmojiText.lastClusterLength("\uD83D\uDC4B\uD83C\uDFFD")) // 👋🏽
    }

    @Test
    fun `une sequence ZWJ part en entier`() {
        // 👩‍💻 = femme + ZWJ + ordinateur
        assertEquals(5, EmojiText.lastClusterLength("x\uD83D\uDC69\u200D\uD83D\uDCBB"))
        // ❤️‍🔥 = coeur + FE0F + ZWJ + feu
        assertEquals(5, EmojiText.lastClusterLength("\u2764\uFE0F\u200D\uD83D\uDD25"))
    }

    @Test
    fun `un drapeau de pays part en entier et deux drapeaux se suppriment un par un`() {
        val fr = "\uD83C\uDDEB\uD83C\uDDF7"
        val de = "\uD83C\uDDE9\uD83C\uDDEA"
        assertEquals(4, EmojiText.lastClusterLength(fr))
        assertEquals(4, EmojiText.lastClusterLength(fr + de))
        // Trois indicateurs : le dernier est isolé.
        assertEquals(2, EmojiText.lastClusterLength(fr + "\uD83C\uDDE9"))
    }

    @Test
    fun `une touche chiffre entouree part en entier`() {
        assertEquals(3, EmojiText.lastClusterLength("1\uFE0F\u20E3"))
    }

    @Test
    fun `un drapeau regional avec etiquettes part en entier`() {
        // 🏴󠁧󠁢󠁥󠁮󠁧󠁿 = drapeau noir + étiquettes g b e n g + étiquette de fin
        val england = "\uD83C\uDFF4" + "\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
        assertEquals(england.length, EmojiText.lastClusterLength("a$england"))
    }

    @Test
    fun `le ZWJ d une ecriture non emoji ne colle pas les lettres`() {
        assertEquals(1, EmojiText.lastClusterLength("ab\u200Dc"))
        assertEquals(1, EmojiText.lastClusterLength("\u0915\u094D\u200D\u0937"))
    }

    @Test
    fun `un emoji apres du texte ne mange pas le texte`() {
        assertEquals(2, EmojiText.lastClusterLength("ab \uD83D\uDE00"))
        assertEquals(2, EmojiText.lastClusterLength("a\uD83D\uDE00"))
    }
}
