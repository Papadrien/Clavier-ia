package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Langues ajoutées après FR et EN : espagnol, allemand, italien, portugais, néerlandais. */
class KeyboardLanguagesLayoutTest {

    private fun letters(language: KeyboardLanguage) = Keyboards.layoutOf(LayoutId.LETTERS, language)

    private fun topRow(language: KeyboardLanguage) =
        letters(language).rows.first().mapNotNull { (it.action as? KeyAction.TypeChar)?.char }.joinToString("")

    private fun key(language: KeyboardLanguage, char: Char) =
        letters(language).rows.flatten().first { (it.action as? KeyAction.TypeChar)?.char == char }

    private val qwertyLanguages = listOf(KeyboardLanguage.ES, KeyboardLanguage.IT, KeyboardLanguage.PT, KeyboardLanguage.NL)

    @Test
    fun `espagnol italien portugais et neerlandais sont en QWERTY`() {
        qwertyLanguages.forEach { assertEquals("qwertyuiop", topRow(it), "$it") }
    }

    @Test
    fun `l allemand est en QWERTZ avec z et y echanges`() {
        assertEquals("qwertzuiop", topRow(KeyboardLanguage.DE))
        val bottom = letters(KeyboardLanguage.DE).rows[2].mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
        assertEquals("yxcvbnm", bottom.joinToString(""))
    }

    @Test
    fun `l espagnol a une touche n tilde a droite du l`() {
        val row = letters(KeyboardLanguage.ES).rows[1].mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
        assertEquals("asdfghjklñ", row.joinToString(""))
    }

    @Test
    fun `les appuis longs donnent les accents de la langue`() {
        assertEquals('ä', key(KeyboardLanguage.DE, 'a').defaultPopupChar)
        assertEquals('ö', key(KeyboardLanguage.DE, 'o').defaultPopupChar)
        assertEquals('ü', key(KeyboardLanguage.DE, 'u').defaultPopupChar)
        assertTrue('ß' in key(KeyboardLanguage.DE, 's').popup.flatten())
        assertEquals('á', key(KeyboardLanguage.ES, 'a').defaultPopupChar)
        assertEquals('à', key(KeyboardLanguage.IT, 'a').defaultPopupChar)
        assertEquals('ã', key(KeyboardLanguage.PT, 'a').popup.flatten().first { it == 'ã' })
        assertTrue('ç' in key(KeyboardLanguage.PT, 'c').popup.flatten())
        assertEquals('é', key(KeyboardLanguage.NL, 'e').defaultPopupChar)
    }

    @Test
    fun `la bulle du point espagnol propose les signes d ouverture`() {
        val popup = letters(KeyboardLanguage.ES).rows.last().first { it.id == "period" }.popup.flatten()
        assertTrue('¿' in popup && '¡' in popup)
        assertTrue('?' in popup && '!' in popup)
        val frPopup = letters(KeyboardLanguage.FR).rows.last().first { it.id == "period" }.popup.flatten()
        assertFalse('¿' in frPopup)
    }

    @Test
    fun `chaque langue contient les 26 lettres de l alphabet`() {
        KeyboardLanguage.entries.forEach { language ->
            val letters = letters(language).rows.flatten()
                .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
                .filter { it in 'a'..'z' }
            assertEquals(('a'..'z').toList(), letters.sorted(), "$language")
        }
    }

    @Test
    fun `lettersOf avec rangee de chiffres ajoute une rangee pour chaque langue`() {
        KeyboardLanguage.entries.forEach { language ->
            assertEquals(letters(language).rows.size + 1, Keyboards.lettersOf(language, numberRow = true).rows.size, "$language")
        }
    }
}
