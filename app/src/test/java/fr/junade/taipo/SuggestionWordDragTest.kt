package fr.junade.taipo

import fr.junade.taipo.dictionary.WordSuggestion
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Appui long sur une suggestion (23/11/2026) : quels mots se glissent, et quand le doigt est sur la poubelle. */
class SuggestionWordDragTest {

    @Test
    fun `les mots se glissent, pas les adresses e-mail`() {
        assertTrue(SuggestionWordDrag.canDrag(WordSuggestion("bonjour", WordSuggestion.Kind.COMPLETION)))
        assertTrue(SuggestionWordDrag.canDrag(WordSuggestion("Ritsanko", WordSuggestion.Kind.TYPED)))
        assertFalse(SuggestionWordDrag.canDrag(WordSuggestion("a@b.fr", WordSuggestion.Kind.EMAIL)))
    }

    @Test
    fun `poubelle touchee pres de son centre, meme un peu hors de la bande`() {
        assertTrue(SuggestionWordDrag.isOverTrash(x = 200f, y = 20f, centerX = 200f, radius = 20f, height = 40f))
        assertTrue(SuggestionWordDrag.isOverTrash(x = 230f, y = -30f, centerX = 200f, radius = 20f, height = 40f))
        assertFalse(SuggestionWordDrag.isOverTrash(x = 260f, y = 20f, centerX = 200f, radius = 20f, height = 40f))
        assertFalse(SuggestionWordDrag.isOverTrash(x = 200f, y = 200f, centerX = 200f, radius = 20f, height = 40f))
    }
}
