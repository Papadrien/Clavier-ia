package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CorrectionHistoryTest {

    @Test
    fun `un mot corrige retrouve sa forme d'origine`() {
        val history = CorrectionHistory()
        history.record("ecoute", "écoute")
        assertEquals("ecoute", history.originalOf("écoute"))
        assertEquals("ecoute", history.originalOf("Écoute")) // insensible à la casse
        assertNull(history.originalOf("ecoute"))
    }

    @Test
    fun `une correction de plusieurs mots retient chaque mot modifie`() {
        val history = CorrectionHistory()
        history.record("réussir a finir", "réussir à finir")
        assertEquals("a", history.originalOf("à"))
        assertNull(history.originalOf("réussir"))
    }

    @Test
    fun `la ponctuation collee au mot est ignoree`() {
        val history = CorrectionHistory()
        history.record("eet,", "est,")
        assertEquals("eet", history.originalOf("est"))
    }

    @Test
    fun `un changement de casse seul n'est pas une correction`() {
        val history = CorrectionHistory()
        history.record("bonjour", "Bonjour")
        assertNull(history.originalOf("Bonjour"))
    }

    @Test
    fun `des textes de longueurs differentes sont ignores`() {
        val history = CorrectionHistory()
        history.record("jai faim", "j'ai")
        assertNull(history.originalOf("j'ai"))
    }

    @Test
    fun `la memoire est bornee aux corrections les plus recentes`() {
        val history = CorrectionHistory(capacity = 2)
        history.record("aa", "bb")
        history.record("cc", "dd")
        history.record("ee", "ff")
        assertNull(history.originalOf("bb"))
        assertEquals("cc", history.originalOf("dd"))
        assertEquals("ee", history.originalOf("ff"))
    }

    @Test
    fun `la correction la plus recente d'un mot remplace la precedente`() {
        val history = CorrectionHistory()
        history.record("ecoute", "écoute")
        history.record("ecouet", "écoute")
        assertEquals("ecouet", history.originalOf("écoute"))
    }

    @Test
    fun `clear oublie tout`() {
        val history = CorrectionHistory()
        history.record("ecoute", "écoute")
        history.clear()
        assertNull(history.originalOf("écoute"))
    }
}
