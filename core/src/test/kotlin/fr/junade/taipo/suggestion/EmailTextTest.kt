package fr.junade.taipo.suggestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmailTextTest {

    @Test
    fun `une adresse valide est mise en minuscules`() {
        assertEquals("jean.dupont@gmail.com", EmailText.normalize("Jean.Dupont@Gmail.com"))
        assertEquals("a+b_c%d-e@mail.example.co.uk", EmailText.normalize("a+b_c%d-e@mail.example.co.uk"))
        assertEquals("x@y.fr", EmailText.normalize("  x@y.fr  "))
    }

    @Test
    fun `ce qui n'est pas une adresse complete est refuse`() {
        listOf(
            "", "jean", "jean@", "@gmail.com", "jean@gmail", "jean@gmail.", "jean@gmail.c", "jean@@gmail.com",
            "jean dupont@gmail.com", ".jean@gmail.com", "jean.@gmail.com", "je..an@gmail.com", "jean@-gmail.com",
            "jean@gmail-.com", "jean@gmail..com", "jéan@gmail.com",
        ).forEach { assertNull(EmailText.normalize(it), "« $it »") }
    }

    @Test
    fun `une adresse trop longue est refusee`() {
        val long = "a".repeat(EmailText.MAX_LENGTH) + "@x.fr"
        assertNull(EmailText.normalize(long))
        assertTrue(EmailText.normalize("a".repeat(EmailText.MAX_LENGTH - 5) + "@x.fr") != null)
    }

    @Test
    fun `le jeton qui touche le curseur est la suite de caracteres d'adresse`() {
        assertEquals("jean.d", EmailText.trailingToken("écris à jean.d"))
        assertEquals("jean@gm", EmailText.trailingToken("mail : jean@gm"))
        assertEquals("@jean", EmailText.trailingToken("salut @jean"))
        assertEquals("jean", EmailText.trailingToken("jean"))
        assertEquals("jean", EmailText.trailingToken("(jean"))
    }

    @Test
    fun `rien ne touche le curseur apres une espace ou une lettre accentuee`() {
        assertNull(EmailText.trailingToken(""))
        assertNull(EmailText.trailingToken("bonjour "))
        assertNull(EmailText.trailingToken("jean@x.fr "))
        assertNull(EmailText.trailingToken("éjean@x"), "collé à une lettre non ASCII")
    }

    @Test
    fun `un jeton qui touche le debut d'une fenetre coupee est ignore`() {
        assertNull(EmailText.trailingToken("ean.dupont@gmail.com", truncated = true))
        assertEquals("ean.dupont@gmail.com", EmailText.trailingToken("ean.dupont@gmail.com", truncated = false))
        assertEquals("dupont@gmail.com", EmailText.trailingToken(" dupont@gmail.com", truncated = true))
    }

    @Test
    fun `une adresse terminee est lue malgre l'espace ou la ponctuation de fin`() {
        assertEquals("jean@x.fr", EmailText.completedEmail("écris à jean@x.fr"))
        assertEquals("jean@x.fr", EmailText.completedEmail("écris à jean@x.fr "))
        assertEquals("jean@x.fr", EmailText.completedEmail("écris à jean@x.fr\n"))
        assertEquals("jean@x.fr", EmailText.completedEmail("écris à jean@x.fr."))
        assertEquals("jean@x.fr", EmailText.completedEmail("(jean@x.fr), "))
        assertEquals("jean@x.fr", EmailText.completedEmail("<Jean@X.fr>"))
        assertEquals("jean@x.fr", EmailText.completedEmail("\"jean@x.fr\" "))
    }

    @Test
    fun `une adresse incomplete ou un mot ordinaire ne sont pas une adresse terminee`() {
        assertNull(EmailText.completedEmail("écris à jean@gm "))
        assertNull(EmailText.completedEmail("écris à jean@gmail. "))
        assertNull(EmailText.completedEmail("bonjour tout le monde "))
        assertNull(EmailText.completedEmail("jean@x.fr bonjour "), "seule la dernière adresse du texte, tout à la fin, compte")
        assertNull(EmailText.completedEmail(""))
    }

    @Test
    fun `une adresse dont le debut est coupe n'est pas retenue`() {
        assertNull(EmailText.completedEmail("ean.dupont@gmail.com ", truncated = true))
        assertEquals("jean.dupont@gmail.com", EmailText.completedEmail("a jean.dupont@gmail.com ", truncated = true))
    }

    @Test
    fun `les caracteres d'adresse`() {
        assertTrue("azAZ09._%+-@".all { EmailText.isEmailChar(it) })
        assertFalse(EmailText.isEmailChar(' '))
        assertFalse(EmailText.isEmailChar('é'))
        assertFalse(EmailText.isEmailChar(','))
        assertFalse(EmailText.isEmailChar('\''))
    }
}
