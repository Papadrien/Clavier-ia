package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Réglages « Afficher la touche emoji / virgule / point » : la touche masquée disparaît et la barre d'espace reprend sa largeur. */
class BottomRowKeysTest {

    private val languages = KeyboardLanguage.entries
    private val booleans = listOf(false, true)
    private val pages = listOf(LayoutId.LETTERS, LayoutId.SYMBOLS, LayoutId.SYMBOLS_2)

    private fun KeyboardLayout.bottomRow(): List<Key> = rows.last()

    private fun KeyboardLayout.hasKey(id: String) = rows.flatten().any { it.id == id }

    private fun layout(id: LayoutId, language: KeyboardLanguage, keys: BottomRowKeys, field: FieldType = FieldType.TEXT, numberRow: Boolean = false) =
        Keyboards.layoutOf(id, language, numberRow, field, keys)

    @Test
    fun `tout afficher renvoie la disposition d'origine`() {
        for (language in languages) for (numberRow in booleans) {
            assertSame(
                Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow),
                layout(LayoutId.LETTERS, language, BottomRowKeys.ALL, numberRow = numberRow),
                "$language numberRow=$numberRow",
            )
        }
    }

    @Test
    fun `chaque touche masquee disparait de la rangee du bas, les autres restent`() {
        val cases = mapOf(
            BottomRowKeys(emoji = false) to "emoji",
            BottomRowKeys(comma = false) to "comma",
            BottomRowKeys(period = false) to "period",
        )
        for ((keys, hiddenId) in cases) for (language in languages) {
            val row = layout(LayoutId.LETTERS, language, keys).bottomRow()
            assertFalse(row.any { it.id == hiddenId }, "$language sans $hiddenId")
            val expected = listOf("comma", "emoji", "period").filter { it != hiddenId }
            expected.forEach { id -> assertTrue(row.any { it.id == id }, "$language garde $id") }
            assertTrue(row.any { it.action == KeyAction.Enter } && row.any { it.action == KeyAction.Space })
        }
    }

    @Test
    fun `la barre d'espace reprend la largeur des touches masquees et la rangee garde ses unites`() {
        val everything = listOf(BottomRowKeys.ALL) + listOf(
            BottomRowKeys(emoji = false),
            BottomRowKeys(comma = false),
            BottomRowKeys(period = false),
            BottomRowKeys(emoji = false, comma = false),
            BottomRowKeys(emoji = false, comma = false, period = false),
        )
        for (keys in everything) for (language in languages) for (id in pages) {
            val base = layout(id, language, BottomRowKeys.ALL)
            val changed = layout(id, language, keys)
            val hiddenCount = listOf(keys.emoji, keys.comma, keys.period).count { !it }
            // Sur la page des symboles (SYMBOLS), « < » et « > » remplacent virgule et point : seule la touche emoji est concernée.
            val concerned = if (id == LayoutId.SYMBOLS) (if (!keys.emoji) 1 else 0) else hiddenCount
            val baseSpace = base.bottomRow().first { it.action == KeyAction.Space }
            val space = changed.bottomRow().first { it.action == KeyAction.Space }
            assertEquals(baseSpace.weight + concerned, space.weight, 0.0001f, "$keys $language $id")
            assertEquals(rowUnits(base.bottomRow()), rowUnits(changed.bottomRow()), 0.0001f, "$keys $language $id")
            assertEquals(base.referenceUnits, changed.referenceUnits, 0.0001f)
        }
    }

    @Test
    fun `les autres rangees ne changent pas`() {
        for (language in languages) {
            val base = layout(LayoutId.LETTERS, language, BottomRowKeys.ALL, numberRow = true)
            val changed = layout(LayoutId.LETTERS, language, BottomRowKeys(emoji = false, comma = false, period = false), numberRow = true)
            assertEquals(base.rows.dropLast(1), changed.rows.dropLast(1), "$language")
        }
    }

    @Test
    fun `la page des symboles garde les touches inferieur et superieur, seule la touche emoji suit le reglage`() {
        val keys = BottomRowKeys(emoji = false, comma = false, period = false)
        val page = layout(LayoutId.SYMBOLS, KeyboardLanguage.FR, keys)
        assertTrue(page.hasKey("less") && page.hasKey("greater"))
        assertFalse(page.hasKey("emoji"))
    }

    @Test
    fun `dans les champs e-mail et URL la touche arobase ou barre oblique reste meme sans virgule`() {
        val keys = BottomRowKeys(comma = false)
        assertTrue(layout(LayoutId.LETTERS, KeyboardLanguage.FR, keys, FieldType.EMAIL).hasKey("email_at"))
        assertTrue(layout(LayoutId.LETTERS, KeyboardLanguage.FR, keys, FieldType.URL).hasKey("url_slash"))
        assertFalse(layout(LayoutId.LETTERS, KeyboardLanguage.FR, keys, FieldType.EMAIL).hasKey("comma"))
    }

    @Test
    fun `les pave numeriques ne sont pas touches`() {
        val keys = BottomRowKeys(emoji = false, comma = false, period = false)
        for (type in listOf(FieldType.NUMBER, FieldType.PHONE)) {
            assertSame(
                Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, false, type),
                layout(LayoutId.LETTERS, KeyboardLanguage.FR, keys, type),
                "$type",
            )
        }
    }

    @Test
    fun `sans le point, la bulle de symboles disparait avec lui`() {
        val row = layout(LayoutId.LETTERS, KeyboardLanguage.FR, BottomRowKeys(period = false)).bottomRow()
        assertFalse(row.any { it.id == "period" })
        assertTrue(row.first { it.id == "comma" }.popup.isEmpty())
    }

    @Test
    fun `les variantes sont reutilisees entre deux appels`() {
        val keys = BottomRowKeys(emoji = false)
        assertSame(layout(LayoutId.LETTERS, KeyboardLanguage.FR, keys), layout(LayoutId.LETTERS, KeyboardLanguage.FR, keys))
    }

    @Test
    fun `chaque combinaison a sa propre disposition`() {
        val a = layout(LayoutId.LETTERS, KeyboardLanguage.FR, BottomRowKeys(emoji = false))
        val b = layout(LayoutId.LETTERS, KeyboardLanguage.FR, BottomRowKeys(comma = false))
        assertTrue(a.hasKey("comma") && !a.hasKey("emoji"))
        assertTrue(b.hasKey("emoji") && !b.hasKey("comma"))
    }
}
