package fr.junade.taipo.clipboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Story 2.2 : aperçu sur une ligne du texte copié, et version masquée. */
class ClipboardPreviewTest {

    @Test
    fun `un texte court sur une ligne reste inchange`() {
        assertEquals("Bonjour le monde", ClipboardPreview.oneLine("Bonjour le monde"))
    }

    @Test
    fun `retours a la ligne tabulations et suites d espaces deviennent une seule espace`() {
        assertEquals("Bonjour le monde !", ClipboardPreview.oneLine("  Bonjour\n\n  le   monde\t!  \n"))
    }

    @Test
    fun `les espaces insecables sont aussi reduites`() {
        assertEquals("a b", ClipboardPreview.oneLine("a\u00A0\u00A0b"))
    }

    @Test
    fun `un texte fait uniquement d espaces donne un apercu vide`() {
        assertEquals("", ClipboardPreview.oneLine(" \n\t  "))
    }

    @Test
    fun `un texte trop long est coupe et termine par des points de suspension`() {
        assertEquals("a".repeat(10) + ClipboardPreview.ELLIPSIS, ClipboardPreview.oneLine("a".repeat(50), maxCodePoints = 10))
    }

    @Test
    fun `un texte de la longueur maximale n est pas coupe`() {
        assertEquals("a".repeat(10), ClipboardPreview.oneLine("a".repeat(10), maxCodePoints = 10))
    }

    @Test
    fun `la coupe se fait sur un point de code et ne scinde pas un emoji`() {
        val preview = ClipboardPreview.oneLine("\uD83D\uDE00".repeat(5), maxCodePoints = 3)
        assertEquals("\uD83D\uDE00".repeat(3) + ClipboardPreview.ELLIPSIS, preview)
        assertEquals(4, preview.codePointCount(0, preview.length)) // 3 emojis entiers + « … », aucune moitié isolée
    }

    @Test
    fun `une coupe juste apres une espace n en laisse pas devant les points de suspension`() {
        assertEquals("abcde" + ClipboardPreview.ELLIPSIS, ClipboardPreview.oneLine("abcde fghij", maxCodePoints = 6))
    }

    @Test
    fun `un tres gros texte donne un apercu court`() {
        val preview = ClipboardPreview.oneLine("mot ".repeat(200_000), maxCodePoints = 10)
        assertEquals("mot mot mo" + ClipboardPreview.ELLIPSIS, preview)
    }

    @Test
    fun `un contenu sensible est masque`() {
        assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022", ClipboardPreview.forDisplay("4970 1012 3456 7890", sensitive = true))
    }

    @Test
    fun `le masque ne laisse rien voir du texte`() {
        val shown = ClipboardPreview.forDisplay("secret", sensitive = true)
        assertFalse(shown.contains("secret"))
        assertEquals(ClipboardPreview.MASK, shown)
    }

    @Test
    fun `un contenu non sensible montre l apercu`() {
        assertEquals("ligne un ligne deux", ClipboardPreview.forDisplay("ligne un\nligne deux", sensitive = false))
    }

    // --- Story 2.5 : texte des cartes du panneau ---

    @Test
    fun `une carte conserve les retours a la ligne`() {
        assertEquals("ligne un\nligne deux", ClipboardPreview.forCard("  ligne un\nligne deux \n", sensitive = false))
    }

    @Test
    fun `une carte trop longue est coupee sur un point de code`() {
        val card = ClipboardPreview.forCard("\uD83D\uDE00".repeat(10), sensitive = false, maxCodePoints = 4)
        assertEquals("\uD83D\uDE00".repeat(4) + ClipboardPreview.ELLIPSIS, card)
    }

    @Test
    fun `une carte sensible est masquee`() {
        assertEquals(ClipboardPreview.MASK, ClipboardPreview.forCard("secret", sensitive = true))
    }

    @Test
    fun `une carte de la longueur maximale n est pas coupee`() {
        assertEquals("a".repeat(5), ClipboardPreview.forCard("a".repeat(5), sensitive = false, maxCodePoints = 5))
    }
}
