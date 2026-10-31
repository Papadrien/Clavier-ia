package fr.junade.taipo.bench

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

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
    fun `l'autocorrection ne regresse pas par rapport a la reference`() {
        assumeTrue(root != null, "racine du projet introuvable")
        val baseline = AutocorrectBench.readBaseline(root!!)
        assumeTrue(baseline != null, "tools/autocorrect-bench/baseline.txt absent")
        val report = AutocorrectBench.run(root)
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

        assertTrue(problems.isEmpty(), "Régressions de l'autocorrection :\n" + problems.joinToString("\n"))
    }

    private companion object {
        /** Marge en points de pour cent : les mesures sont reproductibles, elle ne sert qu'à absorber un arrondi. */
        const val TOLERANCE = 0.05
    }
}
