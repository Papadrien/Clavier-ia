package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtectedWordsTest {

    @Test
    fun `retrouve un mot personnel present dans le texte sans tenir compte de la casse`() {
        assertEquals(listOf("Junadé"), ProtectedWords.inText("Mon studio junadé publie.", listOf("Junadé")))
    }

    @Test
    fun `ignore les mots personnels absents du texte`() {
        assertEquals(emptyList<String>(), ProtectedWords.inText("Bonjour tout le monde", listOf("Junadé", "Taipo")))
    }

    @Test
    fun `ne retient que les mots entiers`() {
        assertEquals(emptyList<String>(), ProtectedWords.inText("Un taipoteur et le sous-taipo", listOf("Taipo")))
    }

    @Test
    fun `une elision ou une ponctuation colle au mot sans l'empecher`() {
        assertEquals(listOf("Taipo"), ProtectedWords.inText("c'est le clavier de l'Taipo, non ?", listOf("Taipo")))
        assertEquals(listOf("Taipo"), ProtectedWords.inText("Taipo.", listOf("Taipo")))
    }

    @Test
    fun `retrouve un mot apres une occurrence non entiere`() {
        assertEquals(listOf("Taipo"), ProtectedWords.inText("taipoteur puis Taipo", listOf("Taipo")))
    }

    @Test
    fun `l'apostrophe typographique du texte equivaut a l'apostrophe droite du dictionnaire`() {
        assertEquals(listOf("aujourd'hui"), ProtectedWords.inText("aujourd\u2019hui", listOf("aujourd'hui")))
    }

    @Test
    fun `texte ou dictionnaire vide donne une liste vide`() {
        assertEquals(emptyList<String>(), ProtectedWords.inText("", listOf("Taipo")))
        assertEquals(emptyList<String>(), ProtectedWords.inText("Taipo", emptyList()))
    }

    @Test
    fun `plafonne le nombre de mots envoyes`() {
        val words = (1..ProtectedWords.MAX_WORDS + 20).map { "mot${'a' + it % 26}${it}x" }
        val text = words.joinToString(" ")
        assertEquals(ProtectedWords.MAX_WORDS, ProtectedWords.inText(text, words).size)
    }

    @Test
    fun `le tour utilisateur liste les mots proteges avant le texte`() {
        val turn = CorrectionPrompt.userTurn("Bonjour Junadé", listOf("Junadé", "Taipo"))
        assertTrue(turn.contains("Junadé, Taipo"))
        assertTrue(turn.indexOf("Junadé, Taipo") < turn.indexOf("Texte :"))
        assertTrue(turn.endsWith("Texte :\nBonjour Junadé"))
    }

    @Test
    fun `sans mot protege le tour utilisateur ne mentionne rien`() {
        val turn = CorrectionPrompt.userTurn("Bonjour")
        assertFalse(turn.contains("Mots à conserver"))
        assertTrue(turn.endsWith("Texte :\nBonjour"))
    }
}
