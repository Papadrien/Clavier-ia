package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.WordText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 5 : le mot en gras au centre de la bande de suggestions est toujours celui que l'autocorrection applique à
 * l'espace, et inversement. Le texte est tapé mot par mot ; à la fin de chaque mot, on compare la bande
 * ([Dictionary.suggestionSlotsFor] avec [ContextualCorrection.lastWordFix], comme le fait le clavier) et
 * [Autocorrection.correctedText].
 *
 * La bande ne montre que le mot en cours : quand l'autocorrection révise aussi le mot précédent (correction à rebours), il n'y
 * a rien à comparer pour le mot en cours, mais la bande ne doit alors rien montrer.
 */
class AutocorrectionCoherenceTest {

    private val dictionary = Dictionary.withFrequencies(
        listOf(
            "ils", "on", "ont", "fini", "leur", "travail", "il", "va", "a", "à", "la", "là", "plage", "elle", "manger", "mangé",
            "une", "pomme", "sa", "ça", "bien", "ces", "un", "bon", "film", "peu", "peut", "venir", "bonjour", "tout", "le",
            "monde", "je", "vais", "les", "enfants", "joué", "ce", "se", "lève", "tôt", "son", "sont", "frère", "est", "et",
            "fatiguée", "fatigué", "reste", "suis", "pour", "toi", "chat", "chien", "maison", "avis",
        ).associateWith { 5_000L },
        InflectionRules.NONE,
    )

    private val sentences = listOf(
        "Ils on fini leur travail",
        "Il va a la plage",
        "Elle a manger une pomme",
        "Sa va bien",
        "Ces un bon film",
        "Il peu venir",
        "bonjor tout le monde",
        "Les enfants on joué",
        "Il ce lève tôt",
        "Sont frère est fatigué",
        "Reste la pour toi",
        "Elle et fatiguée",
        "Je vais a la maison",
        "Il a un chat et un chien",
    )

    private fun lastWord(text: String) = WordText.trailingWord(text)

    /** Chaque fin de mot de [sentence] : le texte avant le curseur au moment où l'espace est tapée. */
    private fun wordEnds(sentence: String): List<String> {
        val ends = ArrayList<String>()
        var offset = 0
        for (word in sentence.split(' ')) {
            offset += word.length
            ends += sentence.substring(0, offset)
            offset += 1
        }
        return ends
    }

    @Test
    fun `le mot en gras de la bande est celui que l'autocorrection applique`() {
        var compared = 0
        for (sentence in sentences) {
            for (text in wordEnds(sentence)) {
                val typed = lastWord(text)
                val corrected = Autocorrection.correctedText(
                    textBeforeCursor = text,
                    textMayBeTruncated = false,
                    allowRetro = true,
                    dictionary = dictionary,
                    personalWords = emptyList(),
                    contextual = true,
                    habitsFor = { null },
                )
                val fix = ContextualCorrection.lastWordFix(text, false, dictionary::frequencyOf, habitsFor = { null })
                val slots = dictionary.suggestionSlotsFor(typed, emptyList(), null, fix)
                val bold = slots.firstOrNull { it?.kind == WordSuggestion.Kind.AUTOCORRECTION }?.text
                val appliedToLastWord = corrected != text && text.dropLast(typed.length) == corrected.dropLast(lastWord(corrected).length)
                val message = "« $text » : bande=$bold, autocorrection=$corrected"
                if (appliedToLastWord) {
                    assertEquals(lastWord(corrected), bold, message)
                    compared++
                } else {
                    // Rien d'appliqué à ce mot (ou une révision du mot précédent) : la bande ne promet rien.
                    assertEquals(null, bold, message)
                }
            }
        }
        // Le test ne vaut que s'il compare de vraies corrections (dictionnaire, a / à, homophone, participe).
        assertTrue(compared >= 5, "corrections comparées : $compared")
    }

    @Test
    fun `une correction refusee deux fois disparait de la bande comme de l'autocorrection`() {
        val habits = CorrectionHabits()
        repeat(2) { habits.recordUndo(KeyboardLanguage.FR, "on", "ont") }
        // Le contexte réel est construit par le clavier ; ici seule la paire refusée compte.
        val context = HabitContext(habits, KeyboardLanguage.FR, null, "ils")
        val habitsFor = { _: String -> context }
        val corrected = Autocorrection.correctedText("Ils on", false, true, dictionary, emptyList(), true, habitsFor)
        assertEquals("Ils on", corrected)
        val fix = ContextualCorrection.lastWordFix("Ils on", false, dictionary::frequencyOf, habitsFor)
        val slots = dictionary.suggestionSlotsFor("on", emptyList(), context, fix)
        assertTrue(slots.none { it?.kind == WordSuggestion.Kind.AUTOCORRECTION })
    }
}
