package fr.papadrien.clavier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardLayoutTest {

    private val layouts = listOf(Keyboards.letters, Keyboards.symbols)

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

    private fun assertActionPresent(layout: KeyboardLayout, action: KeyAction) {
        assertTrue(
            layout.rows.flatten().any { it.action == action },
            "L'action $action doit être présente sur le layout ${layout.id}",
        )
    }
}