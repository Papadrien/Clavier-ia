package fr.junade.taipo.diagnostic

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.CompactWordTable
import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.dictionary.DictionaryLoader

/**
 * Mesures de l'écran « Diagnostic mémoire » (builds debug) : pour chaque langue, mémoire et temps de lecture de la liste
 * (`HashMap`, lue puis libérée) et de la table compacte que garde le dictionnaire ([CompactWordTable]), vitesse de recherche des deux, puis le
 * dictionnaire complet tel que le clavier le construit (durée de chaque étape du chargement et des premières utilisations),
 * avec la durée de corrections et de suggestions types.
 *
 * Bloquant (plusieurs secondes par langue) : à appeler hors du thread principal. Les mesures de mémoire sont des écarts de
 * tas Java après ramasse-miettes : approximatives (à quelques Mo près), mais comparables entre elles. Aucun mot n'est rapporté,
 * seulement des nombres.
 */
class MemoryDiagnostic(private val context: Context) {

    fun deviceSummary(): String {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        return buildString {
            appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("RAM : ${mb(memory.totalMem)} Mo au total, ${mb(memory.availMem)} Mo libres")
            appendLine("Tas Java max du processus : ${mb(Runtime.getRuntime().maxMemory())} Mo (memoryClass ${activityManager?.memoryClass} Mo)")
            appendLine("Tas utilisé au départ : ${mb(usedHeap())} Mo, natif ${mb(Debug.getNativeHeapAllocatedSize())} Mo")
        }
    }

    /** Mesure [language] ; [report] reçoit chaque ligne dès qu'elle est prête. */
    fun measure(language: KeyboardLanguage, report: (String) -> Unit) {
        val file = DictionaryLoader.fullListFile(context, language)
        if (!file.isFile) {
            report("${language.displayName} : liste non téléchargée, ignorée")
            return
        }
        report("== ${language.displayName} (fichier ${mb(file.length())} Mo)")

        // 1. Liste lue (HashMap, libérée une fois la table compacte construite).
        val base = usedHeap()
        var start = SystemClock.elapsedRealtime()
        var map: Map<String, Long>? = Dictionary.parseFrequencyBytes(file.readBytes(), MIN_FREQUENCY, (file.length() / 35).toInt())
        val parseMs = SystemClock.elapsedRealtime() - start
        val mapBytes = usedHeap() - base
        val entries = map!!.size
        report("Liste lue (HashMap) : $entries mots, ${mb(mapBytes)} Mo, lecture $parseMs ms")

        // 2. Table compacte, celle que garde le dictionnaire, construite depuis la même liste.
        start = SystemClock.elapsedRealtime()
        var compact: CompactWordTable? = CompactWordTable.from(map)
        val compactMs = SystemClock.elapsedRealtime() - start
        val compactBytes = usedHeap() - base - mapBytes
        report(
            "Table compacte : ${mb(compactBytes)} Mo (tableaux ${mb(compact!!.approximateBytes)} Mo), construction $compactMs ms, " +
                "soit ${percent(compactBytes, mapBytes)} de la HashMap",
        )

        // 3. Vitesse de recherche : mots présents et absents, mêmes listes pour les deux tables.
        val words = map.keys.filterIndexed { index, _ -> index % LOOKUP_STRIDE == 0 }.take(LOOKUP_WORDS)
        val probes = words + words.map { it + "q" }
        val mapLookupNs = timeLookups(probes) { map[it] ?: -1L }
        val compactLookupNs = timeLookups(probes) { compact.frequencyOf(it) }
        report("Recherche (ns par mot) : HashMap $mapLookupNs, compacte $compactLookupNs")

        // 4. Mots courants pour les corrections types : les plus fréquents de 5 lettres et plus, deux lettres inversées.
        val typos = map.entries.asSequence().filter { it.key.length >= MIN_TYPO_LENGTH && it.key.all(Char::isLetter) }
            .sortedByDescending { it.value }.take(TYPO_COUNT).map { swapMiddle(it.key) }.toList()
        val prefixes = typos.map { it.take(PREFIX_LENGTH) }
        compact = null
        map = null

        // 5. Dictionnaire complet, comme pour le clavier (table, index, noms, bigrammes).
        val beforeDictionary = usedHeap()
        val timings = DictionaryLoader.LoadTimings()
        start = SystemClock.elapsedRealtime()
        val dictionary = DictionaryLoader.buildUncachedForDiagnostic(context, language, timings)
        val dictionaryMs = SystemClock.elapsedRealtime() - start
        if (dictionary == null) {
            report("Dictionnaire complet : fichiers incomplets, ignoré")
            return
        }
        val dictionaryBytes = usedHeap() - beforeDictionary
        report("Dictionnaire complet : ${mb(dictionaryBytes)} Mo, chargement $dictionaryMs ms (table, index, noms, bigrammes)")
        report("Étapes du chargement : " + timings.millis.entries.joinToString(", ") { "${it.key} ${it.value} ms" })

        // 6. Travail repoussé à la première utilisation (lazy) : fait pendant le préchargement ou à la première frappe.
        val nextLetterMicros = timed { dictionary.nextLetterModel }
        val firstCorrectionMicros = typos.firstOrNull()?.let { typo -> timed { dictionary.correctionFor(typo) } }
        val firstSuggestionMicros = prefixes.firstOrNull()?.let { prefix -> timed { dictionary.suggestionSlotsFor(prefix) } }
        report(
            "Premières utilisations : lettre suivante ${ms(nextLetterMicros)} ms, " +
                "première correction ${firstCorrectionMicros?.let(::ms) ?: "?"} ms, " +
                "premières suggestions ${firstSuggestionMicros?.let(::ms) ?: "?"} ms",
        )

        repeat(WARMUP_ROUNDS) { typos.forEach { dictionary.correctionFor(it) } }
        val corrections = typos.map { typo -> timed { dictionary.correctionFor(typo) } }
        report("Correction à l'espace (${corrections.size} fautes types) : ${stats(corrections)}")
        val suggestions = prefixes.map { prefix -> timed { dictionary.suggestionSlotsFor(prefix) } }
        report("Bande de suggestions (${suggestions.size} débuts de mot) : ${stats(suggestions)}")
        report("")
    }

    private inline fun timeLookups(probes: List<String>, lookup: (String) -> Long): Long {
        var sink = 0L
        repeat(WARMUP_ROUNDS) { probes.forEach { sink += lookup(it) } }
        val start = System.nanoTime()
        repeat(LOOKUP_ROUNDS) { probes.forEach { sink += lookup(it) } }
        val perLookup = (System.nanoTime() - start) / (LOOKUP_ROUNDS.toLong() * probes.size)
        if (sink == Long.MIN_VALUE) error("inatteignable") // garde le résultat vivant (pas d'élimination par le compilateur)
        return perLookup
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000 // microsecondes
    }

    private fun stats(micros: List<Long>): String {
        if (micros.isEmpty()) return "aucune mesure"
        val sorted = micros.sorted()
        fun at(fraction: Double) = sorted[((sorted.size - 1) * fraction).toInt()]
        return "médiane ${ms(at(0.5))} ms, p95 ${ms(at(0.95))} ms, max ${ms(sorted.last())} ms"
    }

    private fun swapMiddle(word: String): String {
        val i = word.length / 2
        return word.substring(0, i - 1) + word[i] + word[i - 1] + word.substring(i + 1)
    }

    /** Tas Java occupé, après plusieurs passages du ramasse-miettes (les objets libérés ne comptent plus). */
    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(GC_PASSES) {
            runtime.gc()
            @Suppress("DEPRECATION")
            System.runFinalization()
            Thread.sleep(GC_PAUSE_MS)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun mb(bytes: Long): String = String.format(java.util.Locale.FRANCE, "%.1f", bytes / 1_048_576.0)

    private fun ms(micros: Long): String = String.format(java.util.Locale.FRANCE, "%.2f", micros / 1_000.0)

    private fun percent(part: Long, whole: Long): String = if (whole <= 0) "?" else "${part * 100 / whole} %"

    private companion object {
        const val MIN_FREQUENCY = 5L // même seuil que DictionaryLoader
        const val LOOKUP_STRIDE = 10
        const val LOOKUP_WORDS = 20_000
        const val LOOKUP_ROUNDS = 5
        const val WARMUP_ROUNDS = 2
        const val TYPO_COUNT = 100
        const val MIN_TYPO_LENGTH = 5
        const val PREFIX_LENGTH = 3
        const val GC_PASSES = 3
        const val GC_PAUSE_MS = 150L
    }
}
