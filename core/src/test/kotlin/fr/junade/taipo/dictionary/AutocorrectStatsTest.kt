package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.AutocorrectStats.Section
import fr.junade.taipo.dictionary.AutocorrectStats.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 6 : compteurs de décision de l'autocorrection ([AutocorrectStats]) et trace de [Dictionary.correctionFor]
 * ([DecisionTrace]). Deux garanties : les compteurs disent ce que le moteur a fait, et les poser ne change jamais le
 * résultat ni ne retient de texte.
 */
class AutocorrectStatsTest {

    private val dictionary = Dictionary.withFrequencies(
        mapOf(
            "bonjour" to 5_000L,
            "monde" to 5_000L,
            "bateau" to 5_000L,
            "gateau" to 5_000L,
            "chat" to 5_000L,
            "chapeau" to 5_000L,
            "maison" to 5_000L,
            "fenetre" to 5_000L,
        ),
        InflectionRules.NONE,
    )

    private fun correct(text: String, stats: AutocorrectStats?, contextual: Boolean = false, habits: HabitContext? = null): String =
        Autocorrection.correctedText(
            textBeforeCursor = text,
            textMayBeTruncated = false,
            allowRetro = true,
            dictionary = dictionary,
            personalWords = emptyList(),
            contextual = contextual,
            habitsFor = { habits },
            stats = stats,
        )

    @Test
    fun `une faute de frappe corrigee est comptee par source et par type de faute`() {
        val stats = AutocorrectStats()
        assertEquals("bonjour", correct("bonjor", stats))
        assertEquals(1L, stats.wordCount())
        assertEquals(1L, stats.appliedCount(Source.DICTIONARY_SEARCH))
        assertEquals(1L, stats.appliedCount(FaultKind.MISSING))
        assertEquals(0L, stats.appliedCount(Source.CONTEXT))
        assertEquals(0L, stats.appliedCount(Source.PHRASE_HABIT))
        assertEquals(0L, stats.refusalCount())
        assertEquals(1L, stats.latencyCount(Section.AUTOCORRECTION))
        assertEquals(0L, stats.latencyCount(Section.AUTOCORRECTION_THREE_EDITS))
    }

    @Test
    fun `un mot valide est examine mais rien n'est compte comme corrige`() {
        val stats = AutocorrectStats()
        assertEquals("bateau", correct("bateau", stats))
        assertEquals(1L, stats.wordCount())
        Source.values().forEach { assertEquals(0L, stats.appliedCount(it), "$it") }
        FaultKind.values().forEach { assertEquals(0L, stats.rejectedByMargin(it), "$it") }
    }

    @Test
    fun `une egalite stricte est comptee comme un refus de marge dans le seau le plus bas`() {
        val stats = AutocorrectStats()
        // « bateau » et « gateau » sont à une faute de « xateau », aussi fréquents l'un que l'autre : pile ou face.
        assertEquals("xateau", correct("xateau", stats))
        assertEquals(1L, stats.wordCount())
        val rejectedKinds = FaultKind.values().filter { stats.rejectedByMargin(it) > 0L }
        assertEquals(1, rejectedKinds.size, "un seul type de faute refusé")
        val kind = rejectedKinds.single()
        assertEquals(1L, stats.rejectedByMargin(kind))
        assertEquals(1L, stats.marginBuckets(kind, accepted = false)[0])
        assertEquals(0L, stats.marginBuckets(kind, accepted = true).sum())
        Source.values().forEach { assertEquals(0L, stats.appliedCount(it), "$it") }
    }

    @Test
    fun `une correction appliquee range sa marge dans le dernier seau quand elle n'a pas de rival`() {
        val stats = AutocorrectStats()
        correct("bonjor", stats)
        // Un seul candidat : marge infinie, dernier seau, côté « appliquées ».
        assertEquals(1L, stats.marginBuckets(FaultKind.MISSING, accepted = true)[AutocorrectStats.MARGIN_BUCKETS - 1])
        assertEquals(0L, stats.marginBuckets(FaultKind.MISSING, accepted = false).sum())
    }

    @Test
    fun `les seaux de marge suivent leurs bornes`() {
        assertEquals(0, AutocorrectStats.marginBucket(0.0))
        assertEquals(0, AutocorrectStats.marginBucket(0.24))
        assertEquals(1, AutocorrectStats.marginBucket(0.25))
        assertEquals(2, AutocorrectStats.marginBucket(0.5))
        assertEquals(3, AutocorrectStats.marginBucket(1.0))
        assertEquals(4, AutocorrectStats.marginBucket(2.0))
        assertEquals(5, AutocorrectStats.marginBucket(4.0))
        assertEquals(6, AutocorrectStats.marginBucket(8.0))
        assertEquals(6, AutocorrectStats.marginBucket(Double.POSITIVE_INFINITY))
        // Autant de libellés que de seaux.
        assertEquals(AutocorrectStats.MARGIN_BUCKETS, AutocorrectStats.MARGIN_LABELS.split("|").size)
    }

    @Test
    fun `une correction annulee deux fois est comptee comme un refus d'habitudes`() {
        val habits = CorrectionHabits()
        repeat(2) { habits.recordUndo(KeyboardLanguage.FR, "bonjor", "bonjour") }
        val context = HabitContext(habits, KeyboardLanguage.FR, null, null)
        val stats = AutocorrectStats()
        assertEquals("bonjor", correct("bonjor", stats, habits = context))
        assertEquals(1L, stats.refusalCount())
        Source.values().forEach { assertEquals(0L, stats.appliedCount(it), "$it") }
    }

    @Test
    fun `une annulation est attribuee a la derniere correction appliquee`() {
        val stats = AutocorrectStats()
        correct("bonjor", stats)
        stats.confirmApplied()
        stats.recordUndone()
        assertEquals(1L, stats.undoneCount(Source.DICTIONARY_SEARCH))
        assertEquals(1L, stats.undoneCount(FaultKind.MISSING))
        assertEquals(0L, stats.undoneUnattributedCount())
        // Une seconde annulation n'a plus de correction à laquelle s'attribuer.
        stats.recordUndone()
        assertEquals(1L, stats.undoneUnattributedCount())
        assertEquals(1L, stats.undoneCount(Source.DICTIONARY_SEARCH))
    }

    @Test
    fun `une correction non confirmee par le clavier ne peut pas etre annulee`() {
        val stats = AutocorrectStats()
        correct("bonjor", stats)
        stats.recordUndone()
        assertEquals(0L, stats.undoneCount(Source.DICTIONARY_SEARCH))
        assertEquals(1L, stats.undoneUnattributedCount())
    }

    @Test
    fun `un mot sans correction efface la correction en attente`() {
        val stats = AutocorrectStats()
        correct("bonjor", stats)
        correct("bateau", stats) // le dernier mot examiné n'a rien changé
        stats.confirmApplied()
        stats.recordUndone()
        assertEquals(0L, stats.undoneCount(Source.DICTIONARY_SEARCH))
        assertEquals(1L, stats.undoneUnattributedCount())
    }

    @Test
    fun `poser les compteurs ne change jamais le resultat`() {
        val frenchDictionary = Dictionary.withFrequencies(
            mapOf(
                "bonjour" to 5_000L, "monde" to 5_000L, "bateau" to 5_000L, "gateau" to 5_000L, "chat" to 5_000L,
                "chapeau" to 5_000L, "maison" to 5_000L, "fenetre" to 5_000L, "ils" to 9_000L, "ont" to 9_000L, "on" to 9_000L,
                "fini" to 5_000L, "il" to 9_000L, "va" to 9_000L, "a" to 9_000L, "à" to 9_000L, "la" to 9_000L, "plage" to 5_000L,
            ),
            InflectionRules.NONE,
            confidence = CorrectionConfidence.STANDARD,
        )
        val inputs = listOf(
            "bonjor", "xateau", "bateau", "chta", "monde", "gatau", "bonjourr", "chapau", "maisson", "fenetr", "Ils on", "Il va a",
            "Ils on fini", "n", "", "chat", "CHAT", "ça va", "x",
        )
        for (contextual in listOf(false, true)) {
            for (input in inputs) {
                val plain = Autocorrection.correctedText(input, false, true, frenchDictionary, emptyList(), contextual, { null })
                val counted = Autocorrection.correctedText(input, false, true, frenchDictionary, emptyList(), contextual, { null }, AutocorrectStats())
                assertEquals(plain, counted, "« $input » contextual=$contextual")
            }
        }
    }

    @Test
    fun `la trace ne change pas la correction du dictionnaire`() {
        for (word in listOf("bonjor", "xateau", "bateau", "chta", "bonjourr", "")) {
            val plain = dictionary.correctionFor(word)
            val traced = dictionary.correctionFor(word, trace = DecisionTrace())
            assertEquals(plain, traced, "« $word »")
        }
    }

    @Test
    fun `la trace d'un candidat sans rival est appliquee avec une marge infinie`() {
        val trace = DecisionTrace()
        assertEquals("bonjour", dictionary.correctionFor("bonjor", trace = trace))
        assertEquals(DecisionTrace.Path.SEARCH, trace.path)
        assertEquals(FaultKind.MISSING, trace.kind)
        assertTrue(trace.accepted)
        assertEquals(Double.POSITIVE_INFINITY, trace.margin)
        assertFalse(trace.refusedByHabits)
        assertFalse(trace.threeEditsSearched)
    }

    @Test
    fun `la trace d'un mot valide reste vide`() {
        val trace = DecisionTrace()
        assertNull(dictionary.correctionFor("bateau", trace = trace))
        assertEquals(DecisionTrace.Path.NONE, trace.path)
        assertNull(trace.kind)
        assertFalse(trace.accepted)
    }

    @Test
    fun `la trace d'une egalite est refusee avec une marge nulle`() {
        val trace = DecisionTrace()
        assertNull(dictionary.correctionFor("xateau", trace = trace))
        assertEquals(DecisionTrace.Path.SEARCH, trace.path)
        assertFalse(trace.accepted)
        assertEquals(0.0, trace.margin)
    }

    @Test
    fun `la recherche a trois erreurs est tracee et mesuree a part`() {
        val longDictionary = Dictionary.withFrequencies(
            mapOf("anticonstitutionnellement" to 5_000L, "bonjour" to 5_000L),
            InflectionRules.NONE,
            confidence = CorrectionConfidence.STANDARD,
        )
        val trace = DecisionTrace()
        // Trois erreurs sur un mot d'au moins 8 lettres, rien à distance 2 ou moins : la recherche longue tourne.
        longDictionary.correctionFor("anticonstitutionnelmnt", trace = trace)
        assertTrue(trace.threeEditsSearched)
        assertEquals(DecisionTrace.Path.SEARCH, trace.path)
        assertEquals(FaultKind.THREE_EDITS, trace.kind)
        val stats = AutocorrectStats()
        Autocorrection.correctedText("anticonstitutionnelmnt", false, true, longDictionary, emptyList(), false, { null }, stats)
        assertEquals(1L, stats.threeEditsSearchCount())
        assertEquals(1L, stats.latencyCount(Section.AUTOCORRECTION_THREE_EDITS))
        assertEquals(0L, stats.latencyCount(Section.AUTOCORRECTION))
    }

    @Test
    fun `le rapport ne contient que des nombres et des types, jamais un mot tape`() {
        val stats = AutocorrectStats()
        val typed = listOf("zzbonjorzz", "xateau", "bonjor", "maisson", "secretissime")
        typed.forEach { correct(it, stats) }
        stats.confirmApplied()
        stats.recordUndone()
        stats.recordLatency(Section.SUGGESTIONS, 120_000L)
        val report = stats.report().joinToString("\n")
        (typed + listOf("bonjour", "bateau", "gateau", "maison")).forEach { word ->
            assertFalse(report.contains(word), "le rapport contient « $word »")
        }
        assertTrue(report.contains("mots=${typed.size}"))
        assertTrue(report.contains("Latence bande de suggestions"))
    }

    @Test
    fun `le rapport omet les sections et les types sans mesure`() {
        val lines = AutocorrectStats().report()
        assertEquals(2, lines.size) // le résumé et la ligne par type : ni marges ni latences
        assertTrue(lines.none { it.startsWith("Latence") })
        assertTrue(lines.none { it.startsWith("Marges") })
    }

    @Test
    fun `reset efface tout`() {
        val stats = AutocorrectStats()
        correct("bonjor", stats)
        stats.confirmApplied()
        stats.recordLatency(Section.SUGGESTIONS, 5_000L)
        stats.reset()
        assertEquals(0L, stats.wordCount())
        assertEquals(0L, stats.appliedCount(Source.DICTIONARY_SEARCH))
        assertEquals(0L, stats.latencyCount(Section.AUTOCORRECTION))
        assertEquals(0L, stats.latencyCount(Section.SUGGESTIONS))
        stats.recordUndone()
        assertEquals(1L, stats.undoneUnattributedCount()) // rien n'était attribuable après le reset
    }

    @Test
    fun `une phrase tapee mot par mot cumule les compteurs`() {
        val stats = AutocorrectStats()
        listOf("bonjor", "monde", "bonjor", "xateau").forEach { correct(it, stats) }
        assertEquals(4L, stats.wordCount())
        assertEquals(2L, stats.appliedCount(Source.DICTIONARY_SEARCH))
        assertEquals(2L, stats.appliedCount(FaultKind.MISSING))
        assertEquals(1L, FaultKind.values().sumOf { stats.rejectedByMargin(it) })
    }
}
