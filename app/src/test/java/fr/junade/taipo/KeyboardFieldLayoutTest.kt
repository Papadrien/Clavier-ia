package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 1.18 : le clavier s'adapte au type de champ (e-mail, URL, numérique, téléphone). */
class KeyboardFieldLayoutTest {

    private fun KeyboardLayout.chars(): List<Char> =
        rows.flatten().mapNotNull { (it.action as? KeyAction.TypeChar)?.char }

    private fun KeyboardLayout.has(char: Char) = char in chars()

    private fun KeyboardLayout.hasKey(id: String) = rows.flatten().any { it.id == id }

    private val languages = KeyboardLanguage.entries
    private val booleans = listOf(false, true)

    @Test
    fun `texte et mot de passe gardent la virgule`() {
        for (type in listOf(FieldType.TEXT, FieldType.PASSWORD)) {
            for (language in languages) for (numberRow in booleans) for (id in listOf(LayoutId.LETTERS, LayoutId.SYMBOLS)) {
                val layout = Keyboards.layoutOf(id, language, numberRow, type)
                assertTrue(layout.hasKey("comma"), "$type $language $id")
                assertFalse(layout.hasKey("email_at") || layout.hasKey("url_slash"))
            }
        }
    }

    @Test
    fun `le type texte par defaut ne change rien`() {
        for (language in languages) for (numberRow in booleans) {
            assertSame(
                Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow),
                Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow, FieldType.TEXT),
            )
        }
    }

    @Test
    fun `e-mail remplace la virgule par arobase sur lettres et symboles`() {
        for (language in languages) for (numberRow in booleans) for (id in listOf(LayoutId.LETTERS, LayoutId.SYMBOLS)) {
            val layout = Keyboards.layoutOf(id, language, numberRow, FieldType.EMAIL)
            assertFalse(layout.hasKey("comma"), "$language $id : la virgule doit disparaître")
            assertTrue(layout.hasKey("email_at"), "$language $id")
            val at = layout.rows.flatten().first { it.id == "email_at" }
            assertEquals(KeyAction.TypeChar('@'), at.action)
        }
    }

    @Test
    fun `e-mail garde la meme structure que le clavier de texte`() {
        for (language in languages) for (numberRow in booleans) {
            val text = Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow, FieldType.TEXT)
            val email = Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow, FieldType.EMAIL)
            assertEquals(text.id, email.id)
            assertEquals(text.rows.map { it.size }, email.rows.map { it.size })
            assertEquals(text.rows.map { row -> row.sumOf { it.weight.toDouble() } }, email.rows.map { row -> row.sumOf { it.weight.toDouble() } })
            // Seule la virgule change : les lettres, l'emoji, l'espace, le point et entrée sont intacts.
            val changed = text.rows.flatten().zip(email.rows.flatten()).filter { (a, b) -> a != b }
            assertEquals(1, changed.size)
            assertEquals("comma", changed.single().first.id)
        }
    }

    @Test
    fun `URL remplace la virgule par une barre oblique`() {
        for (language in languages) for (id in listOf(LayoutId.LETTERS, LayoutId.SYMBOLS)) {
            val layout = Keyboards.layoutOf(id, language, false, FieldType.URL)
            assertFalse(layout.hasKey("comma"))
            val slash = layout.rows.flatten().first { it.id == "url_slash" }
            assertEquals(KeyAction.TypeChar('/'), slash.action)
        }
    }

    @Test
    fun `la virgule reste accessible par appui long sur le point dans les champs e-mail et URL`() {
        for (type in listOf(FieldType.EMAIL, FieldType.URL)) {
            val period = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, false, type)
                .rows.flatten().first { it.id == "period" }
            assertTrue(',' in period.popup.flatten(), "$type")
        }
    }

    @Test
    fun `les variantes sont reutilisees entre deux appels`() {
        assertSame(
            Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, true, FieldType.EMAIL),
            Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, true, FieldType.EMAIL),
        )
    }

    @Test
    fun `champ numerique, pave quel que soit le layout demande`() {
        for (id in listOf(LayoutId.LETTERS, LayoutId.SYMBOLS)) for (language in languages) for (numberRow in booleans) {
            val pad = Keyboards.layoutOf(id, language, numberRow, FieldType.NUMBER)
            assertEquals(LayoutId.PAD, pad.id)
            assertEquals(4, pad.rows.size)
            pad.rows.forEach { assertEquals(4, it.size) }
            assertSame(pad, Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, false, FieldType.NUMBER))
        }
    }

    @Test
    fun `pave numerique avec chiffres une fois chacun, separateurs, moins, effacer, espace et entree`() {
        val pad = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, false, FieldType.NUMBER)
        assertEquals("0123456789".toList(), pad.chars().filter { it.isDigit() }.sorted())
        listOf(',', '.', '-').forEach { assertTrue(pad.has(it), "$it") }
        assertTrue(pad.hasKey("backspace"))
        assertTrue(pad.hasKey("enter"))
        assertTrue(pad.hasKey("space"))
        assertFalse(pad.hasKey("shift") || pad.hasKey("toggle") || pad.hasKey("emoji"))
        assertTrue(pad.rows.flatten().none { it.action is KeyAction.ToggleLayout })
    }

    @Test
    fun `pave telephone avec chiffres, plus, etoile, diese, moins, effacer et entree`() {
        val pad = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.EN, true, FieldType.PHONE)
        assertEquals(LayoutId.PAD, pad.id)
        assertEquals("0123456789".toList(), pad.chars().filter { it.isDigit() }.sorted())
        listOf('+', '*', '#', '-').forEach { assertTrue(pad.has(it), "$it") }
        assertTrue(pad.hasKey("backspace"))
        assertTrue(pad.hasKey("enter"))
        pad.rows.forEach { assertEquals(4, it.size) }
        assertNotNull(pad.rows.flatten().firstOrNull { it.action == KeyAction.Enter })
    }

    @Test
    fun `chaque rangee du pave a la meme largeur totale`() {
        for (type in listOf(FieldType.NUMBER, FieldType.PHONE)) {
            val pad = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, false, type)
            val widths = pad.rows.map { row -> row.sumOf { it.weight.toDouble() } }.toSet()
            assertEquals(1, widths.size, "$type : $widths")
        }
    }
}
