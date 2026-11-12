package fr.junade.taipo.bench

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Garde-fou de la refonte de l'autocorrecteur : le comportement mesuré par [AutocorrectBench] ne doit pas régresser par
 * rapport à `tools/autocorrect-bench/baseline.txt`. Une amélioration passe (on ne vérifie que ce qui se dégrade) ; pour
 * enregistrer une nouvelle référence après une phase voulue : voir docs/autocorrection-bench.md.
 *
 * Ignoré (et non en échec) si les dictionnaires ou la référence sont introuvables, par exemple sur un dépôt partiel.
 */
class AutocorrectBaselineTest {

    private val root: File? = runCatching { AutocorrectBench.findRoot(File(System.getProperty("user.dir"))) }.getOrNull()

    @Test
    fun `l'autocorrection francaise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.FRENCH)

    @Test
    fun `l'autocorrection anglaise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.ENGLISH)

    @Test
    fun `l'autocorrection espagnole ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.SPANISH)

    @Test
    fun `l'autocorrection allemande ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.GERMAN)

    @Test
    fun `l'autocorrection italienne ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.ITALIAN)

    @Test
    fun `l'autocorrection portugaise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.PORTUGUESE)

    @Test
    fun `l'autocorrection neerlandaise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.DUTCH)

    // Langues ajoutées le 11/11/2026 (références du 12/11/2026, réglage LATIN, sans règles de contexte).

    @Test
    fun `l'autocorrection polonaise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.POLISH)

    @Test
    fun `l'autocorrection roumaine ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.ROMANIAN)

    @Test
    fun `l'autocorrection tcheque ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.CZECH)

    @Test
    fun `l'autocorrection hongroise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.HUNGARIAN)

    @Test
    fun `l'autocorrection suedoise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.SWEDISH)

    @Test
    fun `l'autocorrection danoise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.DANISH)

    @Test
    fun `l'autocorrection norvegienne ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.NORWEGIAN)

    @Test
    fun `l'autocorrection finnoise ne regresse pas par rapport a la reference`() = checkNoRegression(AutocorrectBench.FINNISH)

    private fun checkNoRegression(profile: AutocorrectBench.Profile) {
        assumeTrue(root != null, "racine du projet introuvable")
        val baseline = AutocorrectBench.readBaseline(root!!, profile)
        assumeTrue(baseline != null, "${profile.baselinePath} absent")
        val report = AutocorrectBench.run(root, profile)
        val problems = ArrayList<String>()

        for ((key, reference) in baseline!!.metrics) {
            val current = report.metrics[key] ?: continue
            when {
                // Ce qui doit rester haut : bonnes corrections et cas de contexte réussis.
                key.endsWith(".ok") && current < reference - TOLERANCE -> problems += "$key : $current < $reference"
                // Ce qui doit rester bas : mauvaises corrections, fausses corrections, phrases justes changées.
                (key.endsWith(".wrong") || key.endsWith(".faux") || key.startsWith("phrases.")) && current > reference + TOLERANCE ->
                    problems += "$key : $current > $reference"
            }
        }
        report.failingCases.filter { it !in baseline.failingCases }.forEach { problems += "cas de contexte devenu faux : ${it.replace("\t", " | ")}" }
        report.changedSentences.filter { it !in baseline.changedSentences }.forEach { problems += "phrase juste désormais changée : $it" }

        assertTrue(problems.isEmpty(), "Régressions de l'autocorrection (${profile.code}) :\n" + problems.joinToString("\n"))
    }

    private companion object {
        /** Marge en points de pour cent : les mesures sont reproductibles, elle ne sert qu'à absorber un arrondi. */
        const val TOLERANCE = 0.05
    }
}
