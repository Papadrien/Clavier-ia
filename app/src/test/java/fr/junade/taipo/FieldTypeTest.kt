package fr.junade.taipo

import android.text.InputType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldTypeTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun `texte libre et variantes ordinaires restent du texte`() {
        assertEquals(FieldType.TEXT, FieldType.of(text))
        assertEquals(FieldType.TEXT, FieldType.of(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals(FieldType.TEXT, FieldType.of(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertEquals(FieldType.TEXT, FieldType.of(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertEquals(FieldType.TEXT, FieldType.of(text or InputType.TYPE_TEXT_VARIATION_FILTER))
        assertEquals(FieldType.TEXT, FieldType.of(InputType.TYPE_NULL))
    }

    @Test
    fun `adresses e-mail, y compris celles des pages web`() {
        assertEquals(FieldType.EMAIL, FieldType.of(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertEquals(FieldType.EMAIL, FieldType.of(text or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
    }

    @Test
    fun `adresse web`() {
        assertEquals(FieldType.URL, FieldType.of(text or InputType.TYPE_TEXT_VARIATION_URI))
    }

    @Test
    fun `mots de passe`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ).forEach { assertEquals(FieldType.PASSWORD, FieldType.of(text or it)) }
    }

    @Test
    fun `champs numeriques, y compris le code PIN, et telephone`() {
        assertEquals(FieldType.NUMBER, FieldType.of(InputType.TYPE_CLASS_NUMBER))
        assertEquals(
            FieldType.NUMBER,
            FieldType.of(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED),
        )
        assertEquals(FieldType.NUMBER, FieldType.of(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertEquals(FieldType.PHONE, FieldType.of(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun `date et heure gardent le clavier de texte`() {
        assertEquals(FieldType.TEXT, FieldType.of(InputType.TYPE_CLASS_DATETIME))
    }

    @Test
    fun `seul le texte libre autocorrige, met des majuscules et remplace le double espace`() {
        assertTrue(FieldType.TEXT.autoCorrects && FieldType.TEXT.autoCapitalizes && FieldType.TEXT.doubleSpacePeriod)
        assertFalse(FieldType.TEXT.numericPad)
        FieldType.entries.filter { it != FieldType.TEXT }.forEach {
            assertFalse(it.autoCorrects, "$it ne doit pas autocorriger")
            assertFalse(it.autoCapitalizes, "$it ne doit pas mettre de majuscule automatique")
            assertFalse(it.doubleSpacePeriod, "$it ne doit pas remplacer le double espace")
        }
    }

    @Test
    fun `pave numerique pour numerique et telephone uniquement`() {
        assertEquals(setOf(FieldType.NUMBER, FieldType.PHONE), FieldType.entries.filter { it.numericPad }.toSet())
    }
}
