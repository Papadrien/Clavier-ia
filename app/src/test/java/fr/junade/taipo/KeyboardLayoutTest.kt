package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardLayoutTest {

    private val layouts = listOf(Keyboards.letters, Keyboards.lettersEn, Keyboards.symbols)

    @Test
    fun `chaque layout a 4 rangees`() {
        layouts.forEach { layout ->
            assertEquals(4, layout.rows.size, "Layout ${layout.id} doit avoir 4 rangées")
        }
    }

    @Test
    fun `chacune des rangees a au plus 10 touches`() {
        layouts.forEach { layout ->
            layout.rows.forEachIndexed { index, row ->
                assertTrue(row.size <= 10, "Rangée $index de ${layout.id} a ${row.size} touches")
            }
        }
    }

    @Test
    fun `aucune touche ne doit avoir un id vide`() {
        layouts.forEach { layout ->
            layout.rows.flatten().forEach { key ->
                assertTrue(key.id.isNotBlank(), "Touche sans id dans ${layout.id}")
            }
        }
    }

    @Test
    fun `toutes les touches ont un poids positif`() {
        layouts.forEach { layout ->
            layout.rows.flatten().forEach { key ->
                assertTrue(key.weight > 0f, "Poids invalide pour ${key.id} dans ${layout.id}")
            }
        }
    }

    @Test
    fun `le clavier lettres contient exactement les 26 lettres de l alphabet`() {
        val letters = Keyboards.letters.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .filter { it.isLetter() }

        assertEquals(26, letters.size)
        assertEquals("abcdefghijklmnopqrstuvwxyz".toList(), letters.sorted())
    }

    @Test
    fun `le clavier lettres a les touches maj effacer entree et bascule`() {
        val allKeys = Keyboards.letters.rows.flatten()
        assertActionPresent(Keyboards.letters, KeyAction.Shift)
        assertActionPresent(Keyboards.letters, KeyAction.Backspace)
        assertActionPresent(Keyboards.letters, KeyAction.Enter)
        assertActionPresent(Keyboards.letters, KeyAction.ToggleLayout)
        assertTrue(allKeys.count { it.action == KeyAction.Space } == 1)
    }

    @Test
    fun `le clavier symboles a les chiffres 0 a 9`() {
        val digits = Keyboards.symbols.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .filter { it.isDigit() }

        assertEquals(('0'..'9').toList(), digits)
    }

    @Test
    fun `le clavier symboles a la ponctuation et les touches effacer entree bascule`() {
        assertActionPresent(Keyboards.symbols, KeyAction.Backspace)
        assertActionPresent(Keyboards.symbols, KeyAction.Enter)
        assertActionPresent(Keyboards.symbols, KeyAction.ToggleLayout)

        val chars = Keyboards.symbols.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
        listOf('?', ',', '.', '!', '\'', '(', ')', '&', 'é', 'è', 'à', 'ç').forEach { c ->
            assertTrue(c in chars, "Le caractère $c doit être présent sur le clavier symboles")
        }
    }

    @Test
    fun `le clavier symboles contient les accents et symboles speciaux`() {
        val chars = Keyboards.symbols.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }

        listOf('@', '+', '*', '#', '$', '%', '€', '=', '_', '-').forEach { c ->
            assertTrue(c in chars, "Le caractère $c doit être présent sur le clavier symboles")
        }
    }

    @Test
    fun `le clavier lettres anglais contient exactement les 26 lettres de l alphabet`() {
        val letters = Keyboards.lettersEn.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .filter { it.isLetter() }

        assertEquals(26, letters.size)
        assertEquals("abcdefghijklmnopqrstuvwxyz".toList(), letters.sorted())
    }

    @Test
    fun `le clavier lettres anglais suit la disposition qwerty et pas azerty`() {
        val topRow = Keyboards.lettersEn.rows.first()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .joinToString("")

        assertEquals("qwertyuiop", topRow)
    }

    @Test
    fun `le clavier lettres anglais a les touches maj effacer entree et bascule`() {
        val allKeys = Keyboards.lettersEn.rows.flatten()
        assertActionPresent(Keyboards.lettersEn, KeyAction.Shift)
        assertActionPresent(Keyboards.lettersEn, KeyAction.Backspace)
        assertActionPresent(Keyboards.lettersEn, KeyAction.Enter)
        assertActionPresent(Keyboards.lettersEn, KeyAction.ToggleLayout)
        assertTrue(allKeys.count { it.action == KeyAction.Space } == 1)
    }

    @Test
    fun `layoutOf retourne l azerty en francais et le qwerty en anglais`() {
        assertEquals(Keyboards.letters, Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR))
        assertEquals(Keyboards.lettersEn, Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.EN))
        assertEquals(KeyboardLanguage.FR, KeyboardLanguage.entries.first())
    }

    @Test
    fun `layoutOf ignore la langue pour le clavier symboles`() {
        assertEquals(Keyboards.symbols, Keyboards.layoutOf(LayoutId.SYMBOLS, KeyboardLanguage.FR))
        assertEquals(Keyboards.symbols, Keyboards.layoutOf(LayoutId.SYMBOLS, KeyboardLanguage.EN))
    }

    // Story 1.5 : rangée de chiffres activable.

    private val languages = KeyboardLanguage.entries

    private fun lettersWithNumberRow(language: KeyboardLanguage) =
        Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = true)

    @Test
    fun `layoutOf sans rangee de chiffres est le comportement par defaut`() {
        languages.forEach { language ->
            assertEquals(
                Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = false),
                Keyboards.layoutOf(LayoutId.LETTERS, language),
            )
            assertEquals(4, Keyboards.layoutOf(LayoutId.LETTERS, language).rows.size)
        }
    }

    @Test
    fun `la rangee de chiffres ajoute en haut les chiffres 1 a 0 dans l ordre`() {
        languages.forEach { language ->
            val layout = lettersWithNumberRow(language)
            assertEquals(5, layout.rows.size)
            val topRow = layout.rows.first().mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            assertEquals("1234567890".toList(), topRow)
        }
    }

    @Test
    fun `la rangee de chiffres laisse les autres rangees inchangees`() {
        languages.forEach { language ->
            val base = Keyboards.layoutOf(LayoutId.LETTERS, language)
            assertEquals(base.rows, lettersWithNumberRow(language).rows.drop(1))
            assertEquals(base.id, lettersWithNumberRow(language).id)
        }
    }

    @Test
    fun `la rangee de chiffres respecte les regles des layouts`() {
        languages.forEach { language ->
            val layout = lettersWithNumberRow(language)
            val ids = layout.rows.flatten().map { it.id }
            assertEquals(ids.size, ids.distinct().size, "Ids en double dans ${layout.id} ($language)")
            assertTrue(layout.rows.all { it.size <= 10 })
            assertTrue(layout.rows.flatten().all { it.weight > 0f })
        }
    }

    @Test
    fun `la rangee de chiffres est sans effet sur le clavier symboles`() {
        languages.forEach { language ->
            assertEquals(
                Keyboards.symbols,
                Keyboards.layoutOf(LayoutId.SYMBOLS, language, numberRow = true),
            )
        }
    }

    private fun assertActionPresent(layout: KeyboardLayout, action: KeyAction) {
        assertTrue(
            layout.rows.flatten().any { it.action == action },
            "L'action $action doit être présente sur le layout ${layout.id}",
        )
    }
}