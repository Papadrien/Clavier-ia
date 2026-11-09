package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 1.17 : mots proposés dans la bande de suggestions pendant la frappe. */
class DictionarySuggestionsTest {

    private val dictionary = Dictionary.withFrequencies(
        mapOf(
            "bon" to 500L,
            "bonne" to 300L,
            "bonjour" to 100L,
            "bonsoir" to 80L,
            "bonheur" to 50L,
            "merci" to 90L,
            "chat" to 70L,
        ),
    )

    @Test
    fun `les completions sont classees par frequence decroissante`() {
        assertEquals(listOf("bonne", "bonjour", "bonsoir"), dictionary.suggestionsFor("bon"))
    }

    @Test
    fun `la limite est respectee`() {
        assertEquals(listOf("bonne", "bonjour"), dictionary.suggestionsFor("bon", limit = 2))
        assertTrue(dictionary.suggestionsFor("bon", limit = 0).isEmpty())
    }

    @Test
    fun `le mot tape n est jamais propose`() {
        assertTrue("bon" !in dictionary.suggestionsFor("bon"))
        assertTrue(dictionary.suggestionsFor("merci").isEmpty())
    }

    @Test
    fun `la majuscule initiale du mot tape est reprise`() {
        assertEquals(listOf("Bonne", "Bonjour", "Bonsoir"), dictionary.suggestionsFor("Bon"))
    }

    @Test
    fun `une faute de frappe est proposee en premier`() {
        assertEquals(listOf("bonjour"), dictionary.suggestionsFor("bnojour"))
        assertEquals(listOf("Bonjour"), dictionary.suggestionsFor("Bnojour"))
    }

    @Test
    fun `la correction passe avant les completions`() {
        val small = Dictionary.withFrequencies(mapOf("bon" to 500L, "bonjour" to 100L))
        // « bonj » n'est pas un mot : correction à distance 1 (« bon »), puis complétion (« bonjour »).
        assertEquals(listOf("bon", "bonjour"), small.suggestionsFor("bonj"))
    }

    @Test
    fun `a egalite de frequence le mot le plus court puis l ordre alphabetique`() {
        val tied = Dictionary.withFrequencies(mapOf("testing" to 10L, "tester" to 10L, "test" to 10L))
        assertEquals(listOf("test", "tester", "testing"), tied.suggestionsFor("tes"))
    }

    @Test
    fun `les mots personnels passent avant ceux du dictionnaire`() {
        assertEquals(
            listOf("Bonzo", "bonne", "bonjour"),
            dictionary.suggestionsFor("bon", personalWords = listOf("Bonzo")),
        )
    }

    @Test
    fun `un mot personnel en minuscules prend la casse tapee`() {
        assertEquals("Bonzo", dictionary.suggestionsFor("Bon", personalWords = listOf("bonzo")).first())
    }

    @Test
    fun `un mot present a la fois en personnel et au dictionnaire n est propose qu une fois`() {
        assertEquals(
            listOf("bonjour"),
            dictionary.suggestionsFor("bonjou", personalWords = listOf("bonjour")),
        )
    }

    @Test
    fun `rien n est propose pour un mot sans lettre, avec un chiffre ou en majuscules`() {
        assertTrue(dictionary.suggestionsFor("").isEmpty())
        assertTrue(dictionary.suggestionsFor("'").isEmpty())
        assertTrue(dictionary.suggestionsFor("42").isEmpty())
        assertTrue(dictionary.suggestionsFor("ch4t").isEmpty())
        assertTrue(dictionary.suggestionsFor("BON").isEmpty())
    }

    @Test
    fun `un mot sans rapport avec le dictionnaire ne donne rien`() {
        assertTrue(dictionary.suggestionsFor("zzzz").isEmpty())
    }

    // --- Emplacements de la bande : la correction (gras, centre) vs les simples suggestions ---

    private fun kinds(slots: List<WordSuggestion?>) = slots.map { it?.kind }
    private fun texts(slots: List<WordSuggestion?>) = slots.map { it?.text }

    @Test
    fun `une faute donne le mot tape a gauche, la correction au centre et une completion a droite`() {
        val small = Dictionary.withFrequencies(mapOf("bon" to 500L, "bonjour" to 100L))
        val slots = small.suggestionSlotsFor("bonj")
        assertEquals(listOf<String?>("bonj", "bon", "bonjour"), texts(slots))
        assertEquals(
            listOf<WordSuggestion.Kind?>(
                WordSuggestion.Kind.TYPED,
                WordSuggestion.Kind.AUTOCORRECTION,
                WordSuggestion.Kind.COMPLETION,
            ),
            kinds(slots),
        )
    }

    @Test
    fun `seule l autocorrection remplace le mot tape a l espace`() {
        val slots = dictionary.suggestionSlotsFor("bnojour")
        assertEquals(listOf<String?>("bnojour", "bonjour", null), texts(slots))
        assertEquals(listOf(false, true), slots.filterNotNull().map { it.replacesOnSpace })
    }

    @Test
    fun `la casse de la premiere lettre est reprise dans la correction mais le mot tape reste tel quel`() {
        assertEquals(listOf<String?>("Bnojour", "Bonjour", null), texts(dictionary.suggestionSlotsFor("Bnojour")))
    }

    @Test
    fun `sans correction les completions sont de simples suggestions de gauche a droite`() {
        val slots = dictionary.suggestionSlotsFor("bon")
        assertEquals(listOf<String?>("bonne", "bonjour", "bonsoir"), texts(slots))
        assertTrue(slots.filterNotNull().none { it.replacesOnSpace })
        assertTrue(slots.all { it?.kind == WordSuggestion.Kind.COMPLETION })
    }

    @Test
    fun `avec moins de completions les emplacements restants sont vides`() {
        val small = Dictionary.withFrequencies(mapOf("test" to 10L, "tester" to 5L))
        assertEquals(listOf<String?>("tester", null, null), texts(small.suggestionSlotsFor("test")))
    }

    @Test
    fun `un mot personnel n est jamais corrige donc jamais en gras`() {
        val slots = dictionary.suggestionSlotsFor("bonz", personalWords = listOf("bonz"))
        assertTrue(slots.filterNotNull().none { it.replacesOnSpace })
    }

    @Test
    fun `la correction proposee est celle que l autocorrection appliquerait`() {
        for (typed in listOf("bnojour", "mreci", "chta", "bonj")) {
            val correction = dictionary.correctionFor(typed)
            val bold = dictionary.suggestionSlotsFor(typed).filterNotNull().singleOrNull { it.replacesOnSpace }
            assertEquals(correction, bold?.text, typed)
        }
    }

    @Test
    fun `rien dans les emplacements pour un mot sans lettre, avec chiffre ou en majuscules`() {
        for (typed in listOf("", "'", "42", "ch4t", "BON")) {
            assertTrue(dictionary.suggestionSlotsFor(typed).all { it == null }, typed)
        }
    }

    // --- participe passé proposé d'après l'auxiliaire ---

    private val participles = Dictionary.withFrequencies(
        mapOf(
            "observe" to 3_000L, "observé" to 1_900L, "observer" to 2_500L, "observation" to 2_000L,
            "reste" to 150_000L, "resté" to 12_000L, "restera" to 9_000L,
            "arrive" to 150_000L, "arrivé" to 90_000L, "arrivée" to 20_000L,
        ),
    )

    private fun slotTexts(slots: List<WordSuggestion?>) = slots.map { it?.text }
    private fun slotKinds(slots: List<WordSuggestion?>) = slots.map { it?.kind }

    @Test
    fun `un participe sur de lui est au centre en gras avec le mot tape a gauche`() {
        val fix = ParticipleFix("observé", automatic = true)
        val slots = participles.suggestionSlotsFor("observe", participle = fix)
        assertEquals(listOf<String?>("observe", "observé", "observer"), slotTexts(slots))
        assertEquals(
            listOf(WordSuggestion.Kind.TYPED, WordSuggestion.Kind.AUTOCORRECTION, WordSuggestion.Kind.COMPLETION),
            slotKinds(slots),
        )
    }

    @Test
    fun `un participe peu sur est propose au centre sans gras`() {
        val fix = ParticipleFix("resté", automatic = false)
        val slots = participles.suggestionSlotsFor("reste", participle = fix)
        assertEquals("resté", slots[1]?.text)
        assertEquals(WordSuggestion.Kind.COMPLETION, slots[1]?.kind)
        assertEquals(false, slots[1]?.replacesOnSpace)
        // Les complétions l'entourent sans le répéter.
        assertEquals(listOf<String?>("restera", "resté", null), slotTexts(slots))
    }

    @Test
    fun `la majuscule du mot tape est reprise sur le participe`() {
        val slots = participles.suggestionSlotsFor("Observe", participle = ParticipleFix("observé", automatic = true))
        assertEquals("Observé", slots[1]?.text)
    }

    @Test
    fun `un participe accorde n est pas repete sous sa forme masculine`() {
        val slots = participles.suggestionSlotsFor("arrive", participle = ParticipleFix("arrivée", automatic = true))
        assertEquals(listOf<String?>("arrive", "arrivée", null), slotTexts(slots))
    }

    @Test
    fun `une faute de frappe garde sa correction du dictionnaire malgre le participe`() {
        val withFix = dictionary.suggestionSlotsFor("bnojour", participle = ParticipleFix("bnojouré", automatic = true))
        assertEquals(slotTexts(dictionary.suggestionSlotsFor("bnojour")), slotTexts(withFix))
        assertEquals("bonjour", withFix[1]?.text)
    }

    @Test
    fun `sans participe rien ne change`() {
        assertEquals(
            slotTexts(participles.suggestionSlotsFor("reste")),
            slotTexts(participles.suggestionSlotsFor("reste", participle = null)),
        )
    }

    @Test
    fun `un mot deja ecrit apres un auxiliaire propose aussi le participe au centre`() {
        val auto = participles.placementSlotsFor("observe", participle = ParticipleFix("observé", automatic = true))
        assertEquals("observé", auto[1]?.text)
        assertEquals(WordSuggestion.Kind.AUTOCORRECTION, auto[1]?.kind)
        val soft = participles.placementSlotsFor("reste", participle = ParticipleFix("resté", automatic = false))
        assertEquals("resté", soft[1]?.text)
        assertEquals(WordSuggestion.Kind.COMPLETION, soft[1]?.kind)
    }
}
