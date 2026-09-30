package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InflectionRulesTest {

    @Test
    fun `baseOf remonte de la forme fleschie vers le mot de base`() {
        assertEquals("durée", InflectionRule("s").baseOf("durées"))
        assertEquals("cheval", InflectionRule("aux", "al").baseOf("chevaux"))
        assertEquals("city", InflectionRule("ies", "y").baseOf("cities"))
    }

    @Test
    fun `baseOf renvoie null si le suffixe ne correspond pas`() {
        assertNull(InflectionRule("s").baseOf("chat"))
        assertNull(InflectionRule("aux", "al").baseOf("cheval"))
    }

    @Test
    fun `baseOf refuse une base de moins de trois lettres`() {
        assertNull(InflectionRule("s").baseOf("as")) // « as » ne vient pas de « a »
        assertEquals("les", InflectionRule("s").baseOf("less"))
    }

    @Test
    fun `formOf reconstruit la forme fleschie`() {
        assertEquals("durées", InflectionRule("s").formOf("durée"))
        assertEquals("chevaux", InflectionRule("aux", "al").formOf("cheval"))
        assertNull(InflectionRule("aux", "al").formOf("chat"))
    }
}
