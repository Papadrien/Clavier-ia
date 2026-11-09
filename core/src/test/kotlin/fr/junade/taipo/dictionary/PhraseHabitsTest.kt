package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.NextWordModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tournures habituelles : un mot valide est corrigé quand les triplets appris disent clairement autre chose
 * (`PhraseHabits`, `HabitContext.phraseCorrectionFor`, `Dictionary.habitPhraseCorrectionFor`), et les triplets comptent
 * dans le contexte du départage.
 */
class PhraseHabitsTest {

    private val fr = KeyboardLanguage.FR
    private val known = { word: String -> word in setOf("des", "les", "mange", "chat", "chut") }

    // ---------------------------------------------------------------- règle pure

    @Test
    fun `un mot habituel clair, a une faute du mot tape, le remplace`() {
        assertEquals("des", PhraseHabits.choose("les", mapOf("des" to 5), known))
        assertEquals("des", PhraseHabits.choose("les", mapOf("des" to 9, "les" to 1), known))
    }

    @Test
    fun `une inversion de deux lettres compte comme une seule faute`() {
        assertEquals("chat", PhraseHabits.choose("chta", mapOf("chat" to 4), known))
    }

    @Test
    fun `pas assez de fois ou pas assez dominant, rien`() {
        assertNull(PhraseHabits.choose("les", mapOf("des" to 3), known))
        assertNull(PhraseHabits.choose("les", mapOf("des" to 4, "mange" to 2), known)) // 66 % seulement
        assertNull(PhraseHabits.choose("les", mapOf("des" to 8, "les" to 2), known)) // le mot tape fait 20 %
    }

    @Test
    fun `un mot a deux fautes ou plus ou de longueur trop differente n est jamais repris`() {
        assertNull(PhraseHabits.choose("les", mapOf("mange" to 9), known))
        assertNull(PhraseHabits.choose("chat", mapOf("chatte" to 9), { true }))
    }

    @Test
    fun `un mot inconnu n est pas repris, meme tres habituel`() {
        assertNull(PhraseHabits.choose("les", mapOf("des" to 9), { false }))
    }

    @Test
    fun `mots courts, acronymes, chiffres et jetons non mots ecartes`() {
        assertNull(PhraseHabits.choose("la", mapOf("le" to 9), { true }))
        assertNull(PhraseHabits.choose("LES", mapOf("des" to 9), known))
        assertNull(PhraseHabits.choose("les1", mapOf("des" to 9), { true }))
        assertNull(PhraseHabits.choose("les", mapOf("^" to 9, NextWordModel.BARRIER to 9, "\uD83D\uDE00" to 9), { true }))
    }

    @Test
    fun `le mot tape lui-meme n est jamais propose`() {
        assertNull(PhraseHabits.choose("des", mapOf("des" to 9), known))
    }

    // ---------------------------------------------------------------- modèle : triplets et contexte

    @Test
    fun `le modele retient les triplets et les rend`() {
        val model = NextWordModel()
        repeat(3) { model.learn("je mange des ", truncated = false) }
        model.learn("je mange les ", truncated = false)
        assertEquals(3, model.trigramCount("je", "mange", "des"))
        assertEquals(mapOf("des" to 3, "les" to 1), model.trigramFollowers("je", "mange"))
        assertTrue(model.trigramFollowers("tu", "manges").isEmpty())
    }

    @Test
    fun `contexte de deux elements, comme l apprentissage`() {
        assertEquals("mange" to "je", NextWordModel.contextOf("je mange ", truncated = false))
        assertEquals(NextWordModel.SENTENCE_START to null, NextWordModel.contextOf("", truncated = false))
        assertNull(NextWordModel.contextOf("", truncated = true))
        assertEquals("je" to NextWordModel.SENTENCE_START, NextWordModel.contextOf("Bonjour. Je ", truncated = false))
        assertEquals("mange" to null, NextWordModel.contextOf("12 mange ", truncated = false))
        assertEquals("b" to null, NextWordModel.contextOf("a b ", truncated = true))
        assertNull(NextWordModel.contextOf("je mange 12 ", truncated = false))
    }

    // ---------------------------------------------------------------- Dictionary + HabitContext

    private val dictionary = Dictionary(listOf("des", "les", "mange", "chat", "chut"))
    private val habits = CorrectionHabits()
    private val model = NextWordModel().apply { repeat(5) { learn("je mange des ", truncated = false) } }

    private fun context(previous: String? = "mange", before: String? = "je") = HabitContext(habits, fr, model, previous, before)

    @Test
    fun `mot valide corrige d apres la tournure habituelle, casse reprise`() {
        assertNull(dictionary.correctionFor("les", habits = context()))
        assertEquals("des", dictionary.habitPhraseCorrectionFor("les", habits = context()))
        assertEquals("Des", dictionary.habitPhraseCorrectionFor("Les", habits = context()))
    }

    @Test
    fun `sans deux mots de contexte sur, rien`() {
        assertNull(dictionary.habitPhraseCorrectionFor("les", habits = context(before = null)))
        assertNull(dictionary.habitPhraseCorrectionFor("les", habits = context(previous = null)))
        assertNull(dictionary.habitPhraseCorrectionFor("les", habits = context(previous = NextWordModel.SENTENCE_START)))
        assertNull(dictionary.habitPhraseCorrectionFor("les", habits = null))
        assertNull(dictionary.habitPhraseCorrectionFor("les", habits = context(before = "tu")))
    }

    @Test
    fun `un mot personnel n est jamais remplace`() {
        assertNull(dictionary.habitPhraseCorrectionFor("les", personalWords = listOf("Les"), habits = context()))
    }

    @Test
    fun `une seule annulation suffit a ne plus proposer la correction`() {
        assertEquals("des", dictionary.habitPhraseCorrectionFor("les", habits = context()))
        habits.recordUndo(fr, "les", "des")
        assertNull(dictionary.habitPhraseCorrectionFor("les", habits = context()))
    }

    @Test
    fun `une habitude contraire du meme contexte empeche la correction`() {
        repeat(2) { model.learn("je mange les ", truncated = false) } // « les » : 2 sur 7, soit 28 %
        assertNull(dictionary.habitPhraseCorrectionFor("les", habits = context()))
    }

    @Test
    fun `la bande montre en gras la tournure habituelle`() {
        val slots = dictionary.suggestionSlotsFor("les", habits = context())
        assertEquals(WordSuggestion("les", WordSuggestion.Kind.TYPED), slots[0])
        assertEquals(WordSuggestion("des", WordSuggestion.Kind.AUTOCORRECTION), slots[1])
        assertEquals(listOf("des"), dictionary.suggestionsFor("les", habits = context()).take(1))
        // Sans habitudes, la bande est celle d'avant.
        assertTrue(dictionary.suggestionSlotsFor("les").none { it?.kind == WordSuggestion.Kind.AUTOCORRECTION })
    }

    // ---------------------------------------------------------------- triplets dans le départage

    @Test
    fun `les triplets pesent plus que les paires dans le contexte`() {
        val twins = Dictionary(listOf("chat", "chut"))
        val m = NextWordModel()
        repeat(3) { m.learn("x le chat ", truncated = false) }
        repeat(2) { m.learn("voir le chut ", truncated = false) }
        // Une paire : « chat » (3) devance « chut » (2).
        assertEquals("chat", twins.correctionFor("chit", habits = HabitContext(CorrectionHabits(), fr, m, "le", null)))
        // Avec « voir » avant « le » : « chut » = 2 + 4 x 2 = 10 contre 3.
        assertEquals("chut", twins.correctionFor("chit", habits = HabitContext(CorrectionHabits(), fr, m, "le", "voir")))
    }
}
