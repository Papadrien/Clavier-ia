package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrectionDiffTest {

    private fun highlighted(original: String, corrected: String): List<String> =
        CorrectionDiff.changedRanges(original, corrected).map { corrected.substring(it.start, it.endExclusive) }

    @Test
    fun `un texte inchange ne surligne rien`() {
        assertTrue(CorrectionDiff.changedRanges("Bonjour tout le monde", "Bonjour tout le monde").isEmpty())
    }

    @Test
    fun `seul le mot corrige est surligne`() {
        assertEquals(listOf("à"), highlighted("je vais a la plage", "je vais à la plage"))
        assertEquals(listOf("pommes"), highlighted("il mange des pomme", "il mange des pommes"))
    }

    @Test
    fun `plusieurs corrections separees donnent plusieurs zones`() {
        assertEquals(
            listOf("chats", "noirs"),
            highlighted("les chat sont noir", "les chats sont noirs"),
        )
    }

    @Test
    fun `des mots inseres a la suite forment une seule zone sans espaces en bordure`() {
        assertEquals(listOf("x y"), highlighted("a b", "a x y b"))
    }

    @Test
    fun `une apostrophe ajoutee surligne le mot concerne`() {
        assertEquals(listOf("l'homme"), highlighted("voici l homme", "voici l'homme"))
    }

    @Test
    fun `une simple suppression ne surligne rien`() {
        assertTrue(CorrectionDiff.changedRanges("un très grand chat", "un grand chat").isEmpty())
    }

    @Test
    fun `un texte entierement different est surligne en entier`() {
        assertEquals(listOf("salut toi"), highlighted("bonjour vous", "salut toi"))
    }

    @Test
    fun `les positions correspondent au texte corrige`() {
        val corrected = "je vais à la plage"
        val range = CorrectionDiff.changedRanges("je vais a la plage", corrected).single()
        assertEquals(8, range.start)
        assertEquals(9, range.endExclusive)
    }

    @Test
    fun `un tres long texte ne plante pas`() {
        val original = (1..3000).joinToString(" ") { "mot$it" }
        val corrected = (1..3000).joinToString(" ") { "mots$it" }
        assertTrue(CorrectionDiff.changedRanges(original, corrected).isNotEmpty())
    }
}
