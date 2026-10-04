package fr.junade.taipo.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ouverture du clavier dans un champ de texte puis frappe : fluidité (images de la fenêtre du clavier, qui
 * appartient au processus de l'application) et durée des sections de trace du lot 3.6 :
 * chargement des dictionnaires (hors thread principal), ouverture du champ, suggestions, autocorrection.
 *
 * Démarrage à froid : le processus est tué à chaque itération, donc le préchargement des dictionnaires
 * est mesuré à chaque fois.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class KeyboardBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun openAndTypeWithoutProfile() = openAndType(CompilationMode.None())

    @Test
    fun openAndTypeWithProfile() = openAndType(CompilationMode.Partial())

    private fun openAndType(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            TraceSectionMetric(Sections.START_INPUT_VIEW, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(Sections.DICTIONARY_LOAD, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(Sections.SUGGESTIONS, TraceSectionMetric.Mode.Sum),
            TraceSectionMetric(Sections.AUTOCORRECTION, TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = {
            pressHome()
            selectTaipoKeyboard()
        },
    ) {
        startActivityAndWait()
        openKeyboardInPersonalDictionary()
        typeSomeKeys()
    }
}
