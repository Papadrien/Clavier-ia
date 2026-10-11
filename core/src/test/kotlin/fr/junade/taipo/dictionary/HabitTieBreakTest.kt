package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.NextWordModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Phase 4 des habitudes de correction : départage de candidats à distance d'édition égale par les habitudes
 * (paire acceptée, puis contexte, puis usage), dans `Dictionary.correctionFor`. Sans habitudes, rien ne change.
 */
class HabitTieBreakTest {

    private val fr = KeyboardLanguage.FR
    private val en = KeyboardLanguage.EN

    private val habits = CorrectionHabits()
    private val model = NextWordModel()

    private fun context(previous: String? = null, language: KeyboardLanguage = fr) = HabitContext(habits, language, model, previous)

    private fun accept(original: String, corrected: String, times: Int = CorrectionHabits.ACCEPTANCE_THRESHOLD) =
        repeat(times) { habits.recordAccepted(fr, original, corrected) }

    private fun use(word: String, times: Int = 1) = repeat(times) { habits.recordUsage(fr, word) }

    private fun follow(previous: String, word: String, times: Int = 1) =
        repeat(times) { model.learn("$previous $word ", truncated = false) }

    // « chit » est à distance 1 de « chat » et de « chut » (même fréquence) : ambigu sans habitudes.
    private val twins = Dictionary(listOf("chat", "chut"))

    // ---------------------------------------------------------------- non-régression

    private val alphabet = "abcdefghijklmnopqrstuvwxyzéèàç'-"

    private fun randomWord(random: Random): String =
        String(CharArray(random.nextInt(2, 11)) { alphabet[random.nextInt(alphabet.length)] })

    private fun mutate(word: String, random: Random): String {
        val chars = word.toMutableList()
        for (step in 0 until random.nextInt(1, 4)) {
            if (chars.isEmpty()) break
            val i = random.nextInt(chars.size)
            when (random.nextInt(4)) {
                0 -> chars[i] = alphabet[random.nextInt(alphabet.length)]
                1 -> chars.removeAt(i)
                2 -> chars.add(i, alphabet[random.nextInt(alphabet.length)])
                else -> if (i + 1 < chars.size) {
                    val next = chars[i + 1]
                    chars[i + 1] = chars[i]
                    chars[i] = next
                }
            }
        }
        return chars.joinToString("")
    }

    private fun randomDictionary(random: Random): Pair<List<String>, Dictionary> {
        val words = HashSet<String>().apply { while (size < 4000) add(randomWord(random)) }.toList()
        val entries = words.associateWith { random.nextLong(1, 100_000) }
        val rows = listOf(
            "azertyuiop".map { KeyProximity.KeyBox(it, 1f) },
            "qsdfghjklm".map { KeyProximity.KeyBox(it, 1f) },
            "wxcvbn".map { KeyProximity.KeyBox(it, 1f) },
        )
        return words to Dictionary.withFrequencies(entries, proximity = KeyProximity.fromRows(rows))
    }

    @Test
    fun `sans habitudes le resultat est identique a celui d avant`() {
        val random = Random(11)
        val (words, dictionary) = randomDictionary(random)
        val emptyContext = context(previous = "le")
        // Habitudes non vides mais sans rapport avec les mots du dictionnaire : le chemin de calcul est exercé, pas l'effet.
        val unrelatedHabits = CorrectionHabits().apply {
            repeat(5) { recordAccepted(fr, "zzzzzzzzzzzz", "yyyyyyyyyyyy") }
            repeat(5) { recordUsage(fr, "wwwwwwwwwwww") }
        }
        val unrelatedModel = NextWordModel().apply { repeat(3) { learn("zzzzzzzzzzzz vvvvvvvvvvvv ", truncated = false) } }
        val unrelatedContext = HabitContext(unrelatedHabits, fr, unrelatedModel, "zzzzzzzzzzzz")

        var corrected = 0
        repeat(3000) {
            val typed = mutate(words[random.nextInt(words.size)], random)
            val expected = dictionary.correctionFor(typed)
            if (expected != null) corrected++
            assertEquals(expected, dictionary.correctionFor(typed, habits = null), "habits = null : $typed")
            assertEquals(expected, dictionary.correctionFor(typed, habits = emptyContext), "habitudes vides : $typed")
            assertEquals(expected, dictionary.correctionFor(typed, habits = unrelatedContext), "habitudes sans rapport : $typed")
        }
        // Le jeu d'essai doit réellement exercer des corrections, sinon le test ne prouve rien.
        assertTrue(corrected > 100, "corrections trouvées : $corrected")
    }

    @Test
    fun `les habitudes ne changent jamais la distance du candidat retenu`() {
        val random = Random(23)
        val (words, dictionary) = randomDictionary(random)
        // Habitudes au hasard : usage de mots du dictionnaire, paires acceptées vers des mots du dictionnaire.
        repeat(1500) { use(words[random.nextInt(words.size)], random.nextInt(1, 4)) }
        repeat(800) {
            accept(mutate(words[random.nextInt(words.size)], random), words[random.nextInt(words.size)], random.nextInt(1, 4))
        }
        repeat(800) { follow(words[random.nextInt(words.size)], words[random.nextInt(words.size)], random.nextInt(1, 3)) }

        var compared = 0
        var changed = 0
        repeat(3000) {
            val typed = mutate(words[random.nextInt(words.size)], random)
            val previous = words[random.nextInt(words.size)]
            val without = dictionary.correctionFor(typed)
            val with = dictionary.correctionFor(typed, habits = context(previous))
            if (without != null && with != null) {
                compared++
                if (with != without) changed++
                assertEquals(
                    Dictionary.damerauLevenshtein(typed.lowercase(), without.lowercase()),
                    Dictionary.damerauLevenshtein(typed.lowercase(), with.lowercase()),
                    "distance : $typed -> $without / $with",
                )
            }
        }
        assertTrue(compared > 100, "corrections comparées : $compared")
        assertTrue(changed > 0, "les habitudes n'ont jamais joué : le test ne prouve rien")
    }

    // ---------------------------------------------------------------- départage

    @Test
    fun `une paire acceptee leve l ambiguite entre deux candidats a distance egale`() {
        assertNull(twins.correctionFor("chit"))
        accept("chit", "chut")
        assertEquals("chut", twins.correctionFor("chit", habits = context()))
        // Sans le contexte d'habitudes, rien n'a changé.
        assertNull(twins.correctionFor("chit"))
    }

    @Test
    fun `une seule acceptation ne compte pas`() {
        accept("chit", "chut", times = CorrectionHabits.ACCEPTANCE_THRESHOLD - 1)
        assertNull(twins.correctionFor("chit", habits = context()))
        accept("chit", "chut", times = 1)
        assertEquals("chut", twins.correctionFor("chit", habits = context()))
    }

    @Test
    fun `une paire acceptee plus souvent l emporte sur une autre`() {
        accept("chit", "chat", times = 2)
        accept("chit", "chut", times = 3)
        assertEquals("chut", twins.correctionFor("chit", habits = context()))
    }

    @Test
    fun `le contexte departage quand aucune paire n est acceptee`() {
        follow("le", "chut", times = 2)
        assertEquals("chut", twins.correctionFor("chit", habits = context(previous = "le")))
        // Autre mot avant : le contexte ne dit rien, l'ambiguïté reste.
        assertNull(twins.correctionFor("chit", habits = context(previous = "un")))
        assertNull(twins.correctionFor("chit", habits = context(previous = null)))
    }

    @Test
    fun `l usage departage quand ni paire ni contexte ne parlent`() {
        use("chut", times = 1)
        assertEquals("chut", twins.correctionFor("chit", habits = context()))
    }

    @Test
    fun `une paire acceptee passe devant le contexte`() {
        accept("chit", "chat")
        follow("le", "chut", times = 5)
        assertEquals("chat", twins.correctionFor("chit", habits = context(previous = "le")))
    }

    @Test
    fun `le contexte passe devant l usage`() {
        follow("le", "chat", times = 1)
        use("chut", times = 50)
        assertEquals("chat", twins.correctionFor("chit", habits = context(previous = "le")))
    }

    @Test
    fun `un candidat plus loin ne passe jamais devant un plus proche`() {
        // Pour « chotte » : « chatte » est à distance 1, « chuttes » à distance 2 (o -> u, s en plus).
        val dictionary = Dictionary(listOf("chatte", "chuttes"))
        assertEquals(2, Dictionary.damerauLevenshtein("chotte", "chuttes"))
        use("chuttes", times = 100)
        accept("chotte", "chuttes", times = 10)
        assertEquals("chatte", dictionary.correctionFor("chotte", habits = context()))
    }

    @Test
    fun `un mot personnel passe devant les habitudes`() {
        accept("chit", "chut", times = 5)
        use("chut", times = 5)
        assertEquals("chot", twins.correctionFor("chit", personalWords = listOf("chot"), habits = context()))
    }

    @Test
    fun `les habitudes passent devant la proximite des touches`() {
        val azerty = KeyProximity.fromRows(
            listOf(
                "azertyuiop".map { KeyProximity.KeyBox(it, 1f) },
                "qsdfghjklm".map { KeyProximity.KeyBox(it, 1f) },
                listOf(KeyProximity.KeyBox(null, 1.4f)) + "wxcvbn'".map { KeyProximity.KeyBox(it, 1f) } + listOf(KeyProximity.KeyBox(null, 1.6f)),
            ),
        )
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 100L, "coat" to 100L), proximity = azerty)
        // g et h sont voisines : « chat » est la faute de frappe plausible.
        assertEquals("chat", dictionary.correctionFor("cgat"))
        assertEquals("chat", dictionary.correctionFor("cgat", habits = context()))
        accept("cgat", "coat")
        assertEquals("coat", dictionary.correctionFor("cgat", habits = context()))
    }

    @Test
    fun `les habitudes passent devant la frequence du corpus`() {
        val dictionary = Dictionary.withFrequencies(mapOf("chat" to 1_000L, "chut" to 10L))
        assertEquals("chat", dictionary.correctionFor("chit"))
        use("chut", times = 1)
        assertEquals("chut", dictionary.correctionFor("chit", habits = context()))
    }

    @Test
    fun `la casse du mot tape est reappliquee`() {
        accept("chit", "chut")
        assertEquals("Chut", twins.correctionFor("Chit", habits = context()))
    }

    @Test
    fun `les habitudes d une langue ne servent pas a l autre`() {
        accept("chit", "chut")
        assertNull(twins.correctionFor("chit", habits = context(language = en)))
    }

    @Test
    fun `un mot deja correct n est jamais corrige par les habitudes`() {
        use("chut", times = 10)
        assertNull(twins.correctionFor("chat", habits = context()))
        assertNull(twins.correctionFor("chut", habits = context()))
    }

    @Test
    fun `la bande de suggestions affiche la correction que les habitudes vont appliquer`() {
        accept("chit", "chut")
        assertNull(twins.suggestionSlotsFor("chit")[1]?.takeIf { it.kind == WordSuggestion.Kind.AUTOCORRECTION })
        val slots = twins.suggestionSlotsFor("chit", habits = context())
        assertEquals(WordSuggestion("chit", WordSuggestion.Kind.TYPED), slots[0])
        assertEquals(WordSuggestion("chut", WordSuggestion.Kind.AUTOCORRECTION), slots[1])
        assertEquals(listOf("chut"), twins.suggestionsFor("chit", habits = context()).take(1))
        val placement = twins.placementSlotsFor("chit", habits = context())
        assertEquals(WordSuggestion("chut", WordSuggestion.Kind.AUTOCORRECTION), placement[1])
    }

    // ---------------------------------------------------------------- HabitContext, HabitScore, accesseur

    @Test
    fun `le score classe accepte puis contexte puis usage`() {
        assertTrue(HabitScore(2, 0, 0) > HabitScore(0, 99, 99))
        assertTrue(HabitScore(0, 1, 0) > HabitScore(0, 0, 99))
        assertTrue(HabitScore(0, 0, 2) > HabitScore(0, 0, 1))
        assertEquals(HabitScore.NONE, HabitScore(0, 0, 0))
    }

    @Test
    fun `le score lit les trois compteurs sans tenir compte de la casse`() {
        accept("chit", "chut", times = 3)
        follow("le", "chut", times = 2)
        use("chut", times = 4)
        assertEquals(HabitScore(3, 2, 4), context(previous = "le").score("Chit", "CHUT"))
        // Une paire acceptée une seule fois vaut 0.
        accept("chet", "chat", times = 1)
        assertEquals(0, context().score("chet", "chat").accepted)
        // Pas de mot précédent : pas de contexte.
        assertEquals(0, context(previous = null).score("chit", "chut").context)
    }

    @Test
    fun `isInert est vrai tant que rien n a ete appris`() {
        assertTrue(context().isInert())
        assertTrue(HabitContext(habits, fr, null, null).isInert())
        use("chut")
        assertFalse(context().isInert())
        habits.clear()
        follow("le", "chat")
        assertFalse(context().isInert())
        assertTrue(HabitContext(habits, fr, null, "le").isInert())
    }

    @Test
    fun `previousWordOf donne le mot qui precede`() {
        assertEquals("le", HabitContext.previousWordOf("Voici le ", truncated = false))
        assertEquals("le", HabitContext.previousWordOf("Voici LE ", truncated = false))
        assertEquals("c'est", HabitContext.previousWordOf("Oui c'est ", truncated = false))
    }

    @Test
    fun `previousWordOf donne le debut de phrase au debut du texte et apres un point`() {
        assertEquals(NextWordModel.SENTENCE_START, HabitContext.previousWordOf("", truncated = false))
        assertEquals(NextWordModel.SENTENCE_START, HabitContext.previousWordOf("Bonjour. ", truncated = false))
        assertEquals(NextWordModel.SENTENCE_START, HabitContext.previousWordOf("Bonjour\n", truncated = false))
    }

    @Test
    fun `previousWordOf ignore un mot peut-etre incomplet dans une fenetre coupee`() {
        // Fenêtre coupée : le premier mot a pu perdre ses premières lettres, il n'est pas un contexte sûr.
        assertNull(HabitContext.previousWordOf("", truncated = true))
        assertEquals("le", HabitContext.previousWordOf("ouvent le ", truncated = true))
        assertNull(HabitContext.previousWordOf("ouvent ", truncated = true))
    }

    @Test
    fun `previousWordOf n a pas de contexte apres un nombre`() {
        assertNull(HabitContext.previousWordOf("Il y a 12 ", truncated = false))
        assertNull(HabitContext.previousWordOf("code AB12 ", truncated = false))
    }

    @Test
    fun `bigramCount lit le modele sans le modifier`() {
        follow("le", "chat", times = 3)
        val size = model.size()
        assertEquals(3, model.bigramCount("le", "chat"))
        assertEquals(0, model.bigramCount("le", "chien"))
        assertEquals(0, model.bigramCount("un", "chat"))
        assertEquals(size, model.size())
        // Début de phrase : le contexte « ^ ».
        model.learn("Chat ", truncated = false)
        assertEquals(1, model.bigramCount(NextWordModel.SENTENCE_START, "chat"))
    }
}
