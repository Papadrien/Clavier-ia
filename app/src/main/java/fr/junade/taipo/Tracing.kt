package fr.junade.taipo

import android.os.Trace

/**
 * Sections de trace système (lot 3.6, P5) : elles apparaissent dans Perfetto / Android Studio Profiler et
 * sont lues par les macrobenchmarks (`TraceSectionMetric`, module `macrobenchmark`). Quand aucune trace
 * n'est en cours, le coût est quasi nul : elles restent donc aussi en release.
 *
 * Début et fin doivent se faire sur le même thread : n'englober que du code qui ne se suspend pas.
 * Noms utilisés (à garder synchronisés avec `macrobenchmark/.../Targets.kt`) : voir [Sections].
 */
object Sections {
    const val START_INPUT_VIEW = "Taipo.startInputView"
    const val DICTIONARY_LOAD = "Taipo.dictionaryLoad"
    const val SUGGESTIONS = "Taipo.suggestions"
    const val AUTOCORRECTION = "Taipo.autocorrection"
    const val EMOJI_CATALOG = "Taipo.emojiCatalog" // lot 21 : lecture + filtrage par la police du catalogue d'emojis
}

inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
