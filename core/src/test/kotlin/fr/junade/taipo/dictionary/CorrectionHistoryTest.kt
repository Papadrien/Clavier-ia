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

    @Test
    fun `une modification de fin de mot retient le mot entier tel que tape`() {
        val history = CorrectionHistory()
        // « cafe » -> « café » : la modification ne remplace que « e » par « é » (début du mot resté en place).
        history.recordEdit("il boit un cafe", deleteCount = 1, insert = "é")
        assertEquals("cafe", history.originalOf("café"))
        assertNull(history.originalOf("é"))
    }

    @Test
    fun `une modification qui commence au debut du mot fonctionne aussi`() {
        val history = CorrectionHistory()
        history.recordEdit("elle ecoute", deleteCount = "ecoute".length, insert = "écoute")
        assertEquals("ecoute", history.originalOf("écoute"))
    }

    @Test
    fun `une correction de contexte sur un mot court retient le mot tel que tape`() {
        val history = CorrectionHistory()
        history.recordEdit("il est alle a", deleteCount = 1, insert = "à")
        assertEquals("a", history.originalOf("à"))
    }

    @Test
    fun `une modification en milieu de mot garde le debut du mot`() {
        val history = CorrectionHistory()
        // « bonjuor » -> « bonjour » : « uor » devient « our », le début « bonj » reste en place.
        history.recordEdit("dire bonjuor", deleteCount = 3, insert = "our")
        assertEquals("bonjuor", history.originalOf("bonjour"))
    }
}
