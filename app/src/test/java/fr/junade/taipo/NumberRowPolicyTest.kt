package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NumberRowPolicyTest {

    @Test
    fun `par defaut la rangee n est affichee nulle part`() {
        for (type in FieldType.entries) assertFalse(NumberRowPolicy.shown(always = false, passwordFieldsOnly = false, fieldType = type))
    }

    @Test
    fun `l option mot de passe n affiche la rangee que dans les champs mot de passe`() {
        assertTrue(NumberRowPolicy.shown(always = false, passwordFieldsOnly = true, fieldType = FieldType.PASSWORD))
        for (type in FieldType.entries.filter { it != FieldType.PASSWORD }) {
            assertFalse(NumberRowPolicy.shown(always = false, passwordFieldsOnly = true, fieldType = type))
        }
    }

    @Test
    fun `la rangee toujours active s affiche partout quelle que soit l option mot de passe`() {
        for (type in FieldType.entries) {
            assertTrue(NumberRowPolicy.shown(always = true, passwordFieldsOnly = false, fieldType = type))
            assertTrue(NumberRowPolicy.shown(always = true, passwordFieldsOnly = true, fieldType = type))
        }
    }
}
