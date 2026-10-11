package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HabitSignalsTest {

    private val fr = KeyboardLanguage.FR
    private val habits = CorrectionHabits()
    private val signals = HabitSignals(habits)

    /** Une correction telle que le clavier l'applique : seule la fin différente du texte est remplacée (« jai » -> « 'ai » après « j »). */
    private class Fix(val before: String, fixed: String) {
        private val common = before.commonPrefixWith(fixed).length
        val original: String = before.substring(common)
        val corrected: String = fixed.substring(common)
    }

    private fun apply(fix: Fix) = signals.autocorrectionApplied(fr, fix.before, fix.original, fix.corrected)

    private fun undo(fix: Fix) = signals.autocorrectionUndone(fix.original, fix.corrected)

    @Test
    fun `un mot termine compte comme un usage`() {
        assertTrue(signals.wordCompleted(fr, "bonjour "))
        assertTrue(signals.wordCompleted(fr, "bonjour tout "))
        assertEquals(1, habits.usageCount(fr, "bonjour"))
        assertEquals(1, habits.usageCount(fr, "tout"))
        assertEquals(2, signals.recordedUsages)
    }

    @Test
    fun `la ponctuation de fin de phrase n'empeche pas de compter le mot`() {
        signals.wordCompleted(fr, "Salut.")
        assertEquals(1, habits.usageCount(fr, "salut"))
    }

    @Test
    fun `un nombre, un code ou un emoji n'est jamais compte`() {
        assertFalse(signals.wordCompleted(fr, "mon code 1234 "))
        assertFalse(signals.wordCompleted(fr, "super \uD83D\uDE05 "))
        assertEquals(0, habits.usageCount(fr, "1234"))
        assertEquals(0, signals.recordedUsages)
    }

    @Test
    fun `un mot corrige automatiquement n'est pas compte comme usage`() {
        apply(Fix("jai", "j'ai"))
        assertFalse(signals.wordCompleted(fr, "j'ai "))
        assertEquals(0, habits.usageCount(fr, "j'ai"))
        assertEquals(0, habits.usageCount(fr, "jai"))
    }

    @Test
    fun `une correction restee en place jusqu'a la fin du mot suivant est acceptee`() {
        apply(Fix("jai", "j'ai"))
        signals.wordCompleted(fr, "j'ai ") // fin du mot corrige lui-meme : rien n'est encore accepte
        assertEquals(0, habits.acceptedCount(fr, "jai", "j'ai"))

        assertTrue(signals.wordCompleted(fr, "j'ai mange "))

        assertEquals(1, habits.acceptedCount(fr, "jai", "j'ai"))
        assertEquals(1, habits.usageCount(fr, "mange"))
        assertEquals(1, signals.recordedAccepted)
    }

    @Test
    fun `une correction n'est acceptee qu'une fois`() {
        apply(Fix("jai", "j'ai"))
        signals.wordCompleted(fr, "j'ai ")
        signals.wordCompleted(fr, "j'ai mange ")
        signals.wordCompleted(fr, "j'ai mange bien ")
        assertEquals(1, habits.acceptedCount(fr, "jai", "j'ai"))
    }

    @Test
    fun `une correction remplacee par autre chose n'est pas acceptee`() {
        apply(Fix("jai", "j'ai"))
        signals.wordCompleted(fr, "j'ai ")
        // L'utilisateur a efface le mot corrige et ecrit autre chose avant de terminer le mot suivant.
        signals.wordCompleted(fr, "je mange ")
        assertEquals(0, habits.acceptedCount(fr, "jai", "j'ai"))
    }

    @Test
    fun `la casse du mot corrige ne change rien`() {
        apply(Fix("Jai", "J'ai"))
        signals.wordCompleted(fr, "J'ai ")
        signals.wordCompleted(fr, "J'ai mange ")
        assertEquals(1, habits.acceptedCount(fr, "jai", "j'ai"))
    }

    @Test
    fun `le point de fin de phrase entre les deux mots est ignore`() {
        apply(Fix("ca", "ça"))
        signals.wordCompleted(fr, "ça ")
        signals.wordCompleted(fr, "ça. Demain ")
        assertEquals(1, habits.acceptedCount(fr, "ca", "ça"))
    }

    @Test
    fun `deux corrections de suite sont suivies chacune`() {
        apply(Fix("jai", "j'ai"))
        signals.wordCompleted(fr, "j'ai ")
        apply(Fix("fait", "fais"))
        signals.wordCompleted(fr, "j'ai fais ") // fin du mot suivant pour la premiere, fin du mot corrige pour la seconde
        assertEquals(1, habits.acceptedCount(fr, "jai", "j'ai"))
        assertEquals(0, habits.acceptedCount(fr, "fait", "fais"))

        signals.wordCompleted(fr, "j'ai fais mal ")
        assertEquals(1, habits.acceptedCount(fr, "fait", "fais"))
    }

    @Test
    fun `une annulation juste apres la correction est comptee`() {
        apply(Fix("jai", "j'ai"))
        signals.wordCompleted(fr, "j'ai ")

        assertTrue(undo(Fix("jai", "j'ai")))

        assertEquals(1, habits.undoCount(fr, "jai", "j'ai"))
        assertEquals(1, signals.recordedUndos)
        // Annulée, la correction ne sera pas acceptee ensuite.
        signals.wordCompleted(fr, "jai mange ")
        signals.wordCompleted(fr, "jai mange bien ")
        assertEquals(0, habits.acceptedCount(fr, "jai", "j'ai"))
    }

    @Test
    fun `une annulation avant la fin du mot est comptee aussi`() {
        apply(Fix("jai", "j'ai"))
        assertTrue(undo(Fix("jai", "j'ai")))
        assertEquals(1, habits.undoCount(fr, "jai", "j'ai"))
    }

    @Test
    fun `l'annulation d'autre chose qu'une autocorrection signalee est ignoree`() {
        // Par exemple une suggestion choisie par l'utilisateur : ce n'est pas une autocorrection.
        assertFalse(undo(Fix("teh", "the")))
        apply(Fix("jai", "j'ai"))
        assertFalse(undo(Fix("teh", "the")))
        assertTrue(habits.isEmpty())
    }

    @Test
    fun `reset oublie les corrections en attente`() {
        apply(Fix("jai", "j'ai"))
        signals.wordCompleted(fr, "j'ai ")
        signals.reset()
        signals.wordCompleted(fr, "j'ai mange ")
        assertEquals(0, habits.acceptedCount(fr, "jai", "j'ai"))
        assertFalse(undo(Fix("jai", "j'ai")))
    }

    @Test
    fun `une correction qui change plusieurs mots n'est jamais enregistree et son mot n'est pas compte`() {
        val fix = Fix("a b", "c d")
        apply(fix)
        assertFalse(signals.wordCompleted(fr, "c d "))
        assertFalse(undo(fix))
        assertTrue(habits.isEmpty())
    }

    @Test
    fun `l'annulation est enregistree sur le mot entier, pas sur la fin remplacee`() {
        val fix = Fix("il jai", "il j'ai")
        assertEquals("ai", fix.original) // le clavier ne remplace que la fin du mot
        apply(fix)
        signals.wordCompleted(fr, "il j'ai ")
        assertTrue(undo(fix))
        assertEquals(1, habits.undoCount(fr, "jai", "j'ai"))
        assertEquals(0, habits.undoCount(fr, "ai", "'ai"))
    }

    @Test
    fun `changedWord renvoie le mot entier tape et corrige`() {
        assertEquals("teh" to "the", HabitSignals.changedWord("il a teh", 2, "he"))
        assertEquals("jai" to "j'ai", HabitSignals.changedWord("jai", 2, "'ai"))
        // Correction de contexte sur le mot precedent : un seul mot change.
        assertEquals("a" to "à", HabitSignals.changedWord("il a mange", 7, "à mange"))
    }

    @Test
    fun `changedWord renvoie null quand ce n'est pas exactement un mot qui change`() {
        assertNull(HabitSignals.changedWord("a b", 3, "c d")) // deux mots
        assertNull(HabitSignals.changedWord("paris", 5, "Paris")) // casse seule
        assertNull(HabitSignals.changedWord("dela", 4, "de la")) // un mot devient deux
        assertNull(HabitSignals.changedWord("mot", 10, "x")) // texte incoherent
        assertNull(HabitSignals.changedWord("mot", 3, "mot")) // rien ne change
    }
}
