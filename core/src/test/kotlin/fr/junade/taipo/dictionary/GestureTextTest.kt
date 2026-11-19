package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Saisie par glissement (19/11/2026) : espace automatique et majuscules du mot glissé. */
class GestureTextTest {

    @Test
    fun `une espace precede le mot glisse apres un mot ou une ponctuation`() {
        assertTrue(GestureText.needsSpaceBefore("Bonjour"))
        assertTrue(GestureText.needsSpaceBefore("Bonjour,"))
        assertTrue(GestureText.needsSpaceBefore("Fin."))
    }

    @Test
    fun `pas d espace en debut de champ, apres une espace ou une apostrophe`() {
        assertFalse(GestureText.needsSpaceBefore(""))
        assertFalse(GestureText.needsSpaceBefore("Bonjour "))
        assertFalse(GestureText.needsSpaceBefore("l'"))
        assertFalse(GestureText.needsSpaceBefore("aujourd’"))
        assertFalse(GestureText.needsSpaceBefore("("))
        assertFalse(GestureText.needsSpaceBefore("ligne\n"))
    }

    @Test
    fun `une lettre ou un chiffre tape apres le mot commence un nouveau mot, pas une ponctuation`() {
        assertTrue(GestureText.startsNewWord('a'))
        assertTrue(GestureText.startsNewWord('3'))
        assertFalse(GestureText.startsNewWord(','))
        assertFalse(GestureText.startsNewWord('\''))
    }

    @Test
    fun `casse du mot glisse`() {
        assertEquals("bonjour", GestureText.applyCase("bonjour", shifted = false, capsLock = false))
        assertEquals("Bonjour", GestureText.applyCase("bonjour", shifted = true, capsLock = false))
        assertEquals("BONJOUR", GestureText.applyCase("bonjour", shifted = true, capsLock = true))
        assertEquals("Paris", GestureText.applyCase("Paris", shifted = false, capsLock = false))
        assertEquals("Été", GestureText.applyCase("été", shifted = true, capsLock = false))
    }
}
