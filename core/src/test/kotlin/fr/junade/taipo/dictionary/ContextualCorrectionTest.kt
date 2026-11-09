package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.NextWordModel
import fr.junade.taipo.suggestion.WordText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 5 : les corrections de contexte (homophones, a / à, participes) passent par le même mécanisme d'annulation que
 * celles du dictionnaire (deux annulations bloquent la paire), et les habitudes de l'utilisateur les départagent.
 */
class ContextualCorrectionTest {

    private val fr = KeyboardLanguage.FR
    private val habits = CorrectionHabits()
    private val model = NextWordModel()

    private fun frequencyOf(@Suppress("UNUSED_PARAMETER") word: String): Long = 10_000L

    /** Les habitudes pour le dernier mot du texte, comme le clavier les construit. */
    private fun habitsFor(text: String): HabitContext {
        val before = text.dropLast(WordText.trailingWord(text).length)
        val context = HabitContext.contextOf(before, truncated = false)
        return HabitContext(habits, fr, model, context?.first, context?.second)
    }

    private fun apply(text: String, allowRetro: Boolean = true): String? =
        ContextualCorrection.apply(text, false, allowRetro, ::frequencyOf, ::habitsFor)

    private fun undo(original: String, corrected: String, times: Int) = repeat(times) { habits.recordUndo(fr, original, corrected) }

    private fun follow(previous: String, word: String, times: Int) = repeat(times) { model.learn("$previous $word ", truncated = false) }

    // ---------------------------------------------------------------- annulations

    @Test
    fun `sans habitudes la correction de contexte est appliquee`() {
        assertEquals("Ils ont", apply("Ils on"))
        assertEquals("Ils ont", ContextualCorrection.apply("Ils on", false, true, ::frequencyOf, habitsFor = { null }))
    }

    @Test
    fun `une correction de contexte annulee deux fois n'est plus appliquee`() {
        undo("on", "ont", 1)
        assertEquals("Ils ont", apply("Ils on"))
        undo("on", "ont", 1)
        assertNull(apply("Ils on"))
    }

    @Test
    fun `le refus d'une paire ne touche pas les autres`() {
        undo("on", "ont", 2)
        assertEquals("Il se lève", apply("Il ce lève"))
    }

    @Test
    fun `une correction a rebours annulee deux fois n'est plus appliquee non plus`() {
        assertEquals("On y", apply("Ont y"))
        undo("Ont", "On", 2)
        assertNull(apply("Ont y"))
    }

    @Test
    fun `un participe refuse deux fois n'est plus applique`() {
        assertEquals("Il a mangé", apply("Il a manger"))
        undo("manger", "mangé", 2)
        assertNull(apply("Il a manger"))
    }

    // ---------------------------------------------------------------- départage par les habitudes

    @Test
    fun `un mot que les habitudes mettent la dans ce contexte n'est pas corrige`() {
        follow("ils", "on", 2)
        assertNull(apply("Ils on"))
    }

    @Test
    fun `une seule fois ne prouve rien`() {
        follow("ils", "on", 1)
        assertEquals("Ils ont", apply("Ils on"))
    }

    @Test
    fun `si la correction est plus habituelle que le mot tape, elle est appliquee`() {
        follow("ils", "on", 2)
        follow("ils", "ont", 5)
        assertEquals("Ils ont", apply("Ils on"))
    }

    @Test
    fun `l'usage seul ne contredit pas une correction de contexte`() {
        repeat(50) { habits.recordUsage(fr, "on") }
        assertEquals("Ils ont", apply("Ils on"))
    }

    @Test
    fun `une paire acceptee passe devant le contexte`() {
        follow("ils", "on", 3)
        repeat(CorrectionHabits.ACCEPTANCE_THRESHOLD) { habits.recordAccepted(fr, "on", "ont") }
        assertEquals("Ils ont", apply("Ils on"))
    }

    @Test
    fun `le contexte lu est celui du mot corrige, meme revu a rebours`() {
        // « Ont y » : le mot corrigé est « Ont », en début de phrase ; ce qui suit « ils » n'a rien à y voir.
        follow("ils", "ont", 4)
        assertEquals("On y", apply("Ont y"))
    }

    // ---------------------------------------------------------------- bande de suggestions

    @Test
    fun `la bande lit la correction du mot en cours de frappe`() {
        val fix = ContextualCorrection.lastWordFix("Ils on", false, ::frequencyOf, ::habitsFor)
        assertNotNull(fix)
        assertEquals("ont", fix?.form)
        assertTrue(fix?.automatic == true)
    }

    @Test
    fun `la bande ne montre pas la revision d'un mot precedent`() {
        assertNull(ContextualCorrection.lastWordFix("Ont y", false, ::frequencyOf, ::habitsFor))
        assertNull(ContextualCorrection.lastWordFix("Il ce", false, ::frequencyOf, ::habitsFor))
    }

    @Test
    fun `la bande ne montre pas une correction refusee`() {
        undo("on", "ont", 2)
        assertNull(ContextualCorrection.lastWordFix("Ils on", false, ::frequencyOf, ::habitsFor))
    }

    @Test
    fun `la bande montre le participe attendu et le retire une fois refuse`() {
        assertEquals("mangé", ContextualCorrection.lastWordFix("Il a manger", false, ::frequencyOf, ::habitsFor)?.form)
        undo("manger", "mangé", 2)
        assertNull(ContextualCorrection.lastWordFix("Il a manger", false, ::frequencyOf, ::habitsFor))
    }

    // ---------------------------------------------------------------- dictionnaire

    @Test
    fun `le dictionnaire ne propose plus une correction refusee deux fois`() {
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 5_000L), InflectionRules.NONE)
        val context = HabitContext(habits, fr, model, null)
        assertEquals("chat", dictionary.correctionFor("chxt", habits = context))
        undo("chxt", "chat", 1)
        assertEquals("chat", dictionary.correctionFor("chxt", habits = context))
        undo("chxt", "chat", 1)
        assertNull(dictionary.correctionFor("chxt", habits = context))
        // Sans habitudes (apprentissage désactivé), la correction est celle d'avant.
        assertEquals("chat", dictionary.correctionFor("chxt"))
    }

    @Test
    fun `les habitudes ne contredisent jamais un mot sans contexte`() {
        assertFalse(HabitContext(habits, fr, model, null).keepsTyped("on", "ont"))
        assertFalse(HabitContext(habits, fr, null, "ils").keepsTyped("on", "ont"))
    }
}
