package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Autocorrection de l'anglais (05/11/2026), sur un petit dictionnaire : le dictionnaire lui-même (contractions, flexions,
 * pas d'élision à la française) et les règles de contexte ajoutées ([EnglishConfusions]). Le banc
 * (`AutocorrectBench --lang=en`) mesure l'ensemble sur le vrai dictionnaire.
 */
class EnglishAutocorrectionTest {

    private val frequencies = mapOf(
        "that" to 900_000L, "that's" to 500_000L, "they're" to 300_000L, "they" to 800_000L, "really" to 600_000L,
        "rea" to 369L, "real" to 290_000L, "rely" to 7_000L, "occur" to 20_000L, "occurred" to 15_000L, "there" to 700_000L,
        "the" to 5_000_000L, "here" to 400_000L, "her" to 500_000L, "heir" to 3_000L, "color" to 30_000L, "colour" to 10_000L,
        "travel" to 40_000L, "travelled" to 9_000L, "traveled" to 5_000L, "visit" to 30_000L, "plane" to 20_000L, "great" to 400_000L,
        "movie" to 100_000L, "day" to 500_000L, "umbrella" to 8_000L, "hour" to 60_000L, "university" to 20_000L, "user" to 9_000L,
        "better" to 150_000L, "than" to 500_000L, "then" to 600_000L, "yesterday" to 90_000L, "went" to 200_000L, "over" to 300_000L,
        "cat" to 50_000L, "cats" to 20_000L, "dog" to 60_000L,
    )

    private val dictionary = Dictionary.withFrequencies(
        frequencies, InflectionRules.ENGLISH, 100L, KeyProximity.NONE, confidence = CorrectionConfidence.ENGLISH,
    )

    private fun fix(word: String): String? = dictionary.correctionFor(word, personalWords = emptyList(), habits = null)

    @Test
    fun `une contraction sans apostrophe est retablie avant la lecture comme pluriel`() {
        assertEquals("that's", fix("thats"))
        assertEquals("That's", fix("Thats"))
        assertEquals("they're", fix("theyre"))
    }

    @Test
    fun `l'anglais n'a pas d'elision a la francaise`() {
        // Avant : « therr » devenait « t'herr » (l'élision « t' » du français).
        assertEquals("there", fix("therr"))
        assertEquals("the", fix("thee"))
    }

    @Test
    fun `un mot de base rare ne valide pas une faute de frappe`() {
        // « rea » (369) + « ly » : « realy » n'est pas un adverbe de « rea ».
        assertEquals("really", fix("realy"))
        // Un vrai pluriel d'un mot courant reste intact.
        assertNull(fix("cats"))
    }

    @Test
    fun `une consonne finale non doublee est une faute quand la forme doublee existe`() {
        assertEquals("occurred", fix("occured"))
        // « visit » + « ed » : « visitted » n'existe pas, la forme régulière est juste.
        assertNull(fix("visited"))
        // Les deux orthographes (américaine et britannique) d'un mot listé restent justes.
        assertNull(fix("traveled"))
        assertNull(fix("travelled"))
        assertNull(fix("colour"))
        assertNull(fix("color"))
    }

    /** Frappe [sentence] caractère par caractère, comme le clavier : correction à chaque fin de mot. */
    private fun type(sentence: String): String {
        var text = ""
        for (c in sentence) {
            if (!(c.isLetter() || c == '\'' || c == '-') && text.isNotEmpty() && !text.last().isWhitespace()) {
                text = EnglishConfusions.fix(text, false, true, dictionary::frequencyOf)
            }
            text += c
        }
        return EnglishConfusions.fix(text, false, true, dictionary::frequencyOf)
    }

    @Test
    fun `a devient an devant un son voyelle, y compris u et h muet`() {
        assertEquals("He needs an umbrella.", type("He needs a umbrella."))
        assertEquals("Wait an hour please.", type("Wait a hour please."))
        assertEquals("She has an idea.", type("She has a idea."))
    }

    @Test
    fun `a reste a devant un u prononce you ou un h sonore`() {
        assertEquals("She teaches at a university.", type("She teaches at a university."))
        assertEquals("We need a user manual.", type("We need a user manual."))
        assertEquals("He has a house.", type("He has a house."))
    }

    @Test
    fun `great grate et their there selon le contexte`() {
        assertEquals("That was a great movie.", type("That was a grate movie."))
        assertEquals("There is a metal grate over the drain.", type("There is a metal grate over the drain."))
        assertEquals("I went over there yesterday.", type("I went over their yesterday."))
    }

    @Test
    fun `then devient than apres better`() {
        assertEquals("This is better than I expected.", type("This is better then I expected."))
        assertEquals("Do it and then go.", type("Do it and then go."))
    }
}
