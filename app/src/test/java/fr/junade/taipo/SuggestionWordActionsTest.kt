package fr.junade.taipo

import fr.junade.taipo.dictionary.WordSuggestion
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Appui long sur une suggestion (23/11/2026) : tous les mots ouvrent le pop-up du dictionnaire, pas les adresses e-mail. */
class SuggestionWordActionsTest {

    @Test
    fun `les mots s'editent, pas les adresses e-mail`() {
        assertTrue(SuggestionWordActions.canEdit(WordSuggestion("bonjour", WordSuggestion.Kind.COMPLETION)))
        assertTrue(SuggestionWordActions.canEdit(WordSuggestion("Ritsanko", WordSuggestion.Kind.TYPED)))
        assertFalse(SuggestionWordActions.canEdit(WordSuggestion("a@b.fr", WordSuggestion.Kind.EMAIL)))
    }

    @Test
    fun `l'astuce du dictionnaire vient a la cinquieme suggestion touchee`() {
        val counter = GestureTips.Counter()
        repeat(GestureTips.SUGGESTIONS_FOR_TIP - 1) { assertFalse(counter.onSuggestionTapped()) }
        assertTrue(counter.onSuggestionTapped())
        assertFalse(counter.onSuggestionTapped())
    }
}
