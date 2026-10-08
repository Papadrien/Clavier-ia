package fr.junade.taipo.bench

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import fr.junade.taipo.BuildConfig
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.dictionary.InflectionRules
import fr.junade.taipo.dictionary.KeyProximity
import fr.junade.taipo.dictionary.KeyProximityFactory
import java.util.Locale
import kotlin.random.Random

/**
 * Banc d'essai de l'autocorrection française (debug uniquement) : compare, sur la même liste de mots et les mêmes
 * fautes générées (graine fixe, donc reproductible) :
 *
 * - A1 : moteur actuel de Taipo (pré-filtre par les lettres + proximité des touches), avec trois réglages du coût
 *   d'une lettre manquante ;
 * - A0 : moteur actuel sans proximité (pour voir ce qu'elle apporte) ;
 * - « sans pré-filtre » : A1 avec l'ancien parcours complet des candidats (mêmes corrections, pour la latence) ;
 * - B  : SymSpellKt brut (voir [SymSpellEngine]).
 *
 * Mesure la mémoire (PSS, tas Java, natif), le temps de construction, la latence d'une correction
 * (p50 / p95 / p99 / max) et la qualité (bonnes corrections, mauvaises, fautes non corrigées, mots justes
 * corrigés à tort). Chaque ligne est écrite dans le logcat (tag [TAG], préfixe « BENCH| ») et renvoyée à [execute].
 *
 * Sans appel à adb : filtrer le logcat d'Android Studio sur « TaipoBench », ou lire/copier l'écran de [BenchActivity].
 */
object SpellBench {

    private const val TAG = "TaipoBench"
    private const val MIN_FREQUENCY = 5L
    private const val CANDIDATE_MIN_FREQUENCY = 100L
    private const val CASES_PER_KIND = 150
    private const val CORRECT_WORDS = 1000
    private const val GIBBERISH_WORDS = 200
    private const val WARMUP = 300

    private val PLAIN = Regex("[a-z]{5,12}")
    private val ACCENTED = Regex("[a-zàâäéèêëîïôöùûüçœæÿ]{4,12}")
    private val ACCENT_CHARS = "àâäéèêëîïôöùûüçœæÿ"
    private val WITH_APOSTROPHE = Regex("[a-z]{1,3}'[a-z]{2,10}")

    private fun interface Corrector {
        /** Correction de [word], ou null si le mot reste inchangé. */
        fun correct(word: String): String?
    }

    private class Case(val kind: String, val typed: String, val expected: String)

    private class Workload(val errors: List<Case>, val correct: List<String>, val gibberish: List<String>)

    private class Row(
        val name: String,
        val okPercent: Double,
        val wrongPercent: Double,
        val missedPercent: Double,
        val falsePositivePercent: Double,
        val p50ErrorUs: Double,
        val p95ErrorUs: Double,
        val p95CorrectUs: Double,
        val p95GibberishUs: Double,
        val maxUs: Double,
    )

    /** Lance le banc d'essai (bloquant : à appeler hors du thread principal). Chaque ligne passe par [out]. */
    fun execute(context: Context, out: (String) -> Unit) {
        val say = { line: String ->
            Log.i(TAG, "BENCH| $line")
            out(line)
        }
        try {
            runAll(context, say)
        } catch (e: Throwable) {
            Log.e(TAG, "BENCH| échec", e)
            out("ÉCHEC : $e")
        }
    }

    private fun runAll(context: Context, say: (String) -> Unit) {
        say("=== Banc d'essai de l'autocorrection Taipo ===")
        say("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android API ${Build.VERSION.SDK_INT}")
        say("Build debug : ${BuildConfig.DEBUG} (latences plus lentes qu'en release : comparer les moteurs entre eux)")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        say("Mémoire autorisée : ${am.memoryClass} Mo (${am.largeMemoryClass} Mo en grosse classe), appareil à mémoire basse : ${am.isLowRamDevice}")

        val readStart = System.nanoTime()
        val entries = context.assets.open("dictionaries/fr.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, MIN_FREQUENCY)
        }
        val candidateCount = entries.count { it.value >= CANDIDATE_MIN_FREQUENCY }
        say("Liste française lue : ${entries.size} mots valides, $candidateCount candidats, en ${millis(readStart)} ms")

        val proximity = KeyProximityFactory.forLanguage(KeyboardLanguage.FR)
        val workload = buildWorkload(entries, proximity)
        say("Jeu d'essai : ${workload.errors.size} fautes, ${workload.correct.size} mots justes, ${workload.gibberish.size} mots sans candidat")

        val baseKb = memory("base (liste lue, aucun moteur)", say)
        val rows = ArrayList<Row>()
        var deltaAKb = 0L

        run {
            val buildStart = System.nanoTime()
            val a1 = Dictionary.withFrequencies(entries, InflectionRules.FRENCH, CANDIDATE_MIN_FREQUENCY, proximity)
            say("A1 construit en ${millis(buildStart)} ms")
            deltaAKb = memory("après construction de A1", say) - baseKb
            val missing = KeyProximity.DEFAULT_MISSING_LETTER_COST
            rows += bench("A1 actuel + proximité (lettre manquante $missing)", { a1.correctionFor(it) }, workload, say)

            // Variantes du coût d'une lettre manquante : 2 (autant qu'une touche voisine) et 4 (ancien réglage).
            for (cost in listOf(2, 4)) {
                if (cost == missing) continue
                val variant = Dictionary.withFrequencies(
                    entries, InflectionRules.FRENCH, CANDIDATE_MIN_FREQUENCY,
                    KeyProximityFactory.forLanguage(KeyboardLanguage.FR, cost),
                )
                rows += bench("A1 variante : lettre manquante $cost", { variant.correctionFor(it) }, workload, say)
            }

            val a0 = Dictionary.withFrequencies(entries, InflectionRules.FRENCH, CANDIDATE_MIN_FREQUENCY)
            rows += bench("A0 actuel sans proximité", { a0.correctionFor(it) }, workload, say)

            // Même moteur que A1 sans le pré-filtre par les lettres (ancien parcours) : mêmes corrections, plus lent.
            val full = Dictionary.withFrequencies(
                entries, InflectionRules.FRENCH, CANDIDATE_MIN_FREQUENCY, proximity, letterPrefilter = false,
            )
            rows += bench("A1 sans pré-filtre (ancien parcours)", { full.correctionFor(it) }, workload, say)
        }

        val base2Kb = memory("base après libération de A", say)
        var deltaBKb = 0L
        run {
            val buildStart = System.nanoTime()
            val b = SymSpellEngine.build(entries, CANDIDATE_MIN_FREQUENCY)
            say("B (SymSpellKt) construit en ${millis(buildStart)} ms")
            deltaBKb = memory("après construction de B", say) - base2Kb
            rows += bench("B SymSpellKt brut", { b.correct(it) }, workload, say)
            memory("après les corrections de B", say)
        }

        say("")
        say("=== RÉSUMÉ ===")
        say(String.format(Locale.ROOT, "Mémoire ajoutée (PSS) : A1 +%.1f Mo | B +%.1f Mo", deltaAKb / 1024.0, deltaBKb / 1024.0))
        say("(A1 : la liste de mots lue reste en mémoire dans tous les cas ; elle est dans la base.)")
        for (row in rows) {
            say(
                String.format(
                    Locale.ROOT,
                    "%s : bonnes %.1f %% | mauvaises %.1f %% | non corrigées %.1f %% | mots justes corrigés à tort %.1f %%",
                    row.name, row.okPercent, row.wrongPercent, row.missedPercent, row.falsePositivePercent,
                ),
            )
            say(
                String.format(
                    Locale.ROOT,
                    "   latence µs : fautes p50 %.0f / p95 %.0f | mots justes p95 %.0f | sans candidat p95 %.0f | max %.0f",
                    row.p50ErrorUs, row.p95ErrorUs, row.p95CorrectUs, row.p95GibberishUs, row.maxUs,
                ),
            )
        }
        say("=== FIN ===")
    }

    private fun millis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** Force le ramasse-miettes puis relève la mémoire du processus ; renvoie le PSS total en ko. */
    private fun memory(label: String, say: (String) -> Unit): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(150)
        }
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val javaMb = (runtime.totalMemory() - runtime.freeMemory()) / MEGA
        val nativeMb = Debug.getNativeHeapAllocatedSize() / MEGA
        say(
            String.format(
                Locale.ROOT,
                "MÉMOIRE %s : PSS %.1f Mo | tas Java %.1f Mo | natif %.1f Mo",
                label, info.totalPss / 1024.0, javaMb, nativeMb,
            ),
        )
        return info.totalPss.toLong()
    }

    private const val MEGA = 1024.0 * 1024.0

    private fun bench(name: String, corrector: Corrector, workload: Workload, say: (String) -> Unit): Row {
        say("--- $name ---")
        // Chauffe du JIT : non mesurée.
        workload.errors.take(WARMUP).forEach { corrector.correct(it.typed) }

        // Qualité, par type de faute.
        val perKind = LinkedHashMap<String, IntArray>() // [bonnes, mauvaises, non corrigées, total]
        var ok = 0
        var wrong = 0
        var missed = 0
        for (case in workload.errors) {
            val result = corrector.correct(case.typed) ?: case.typed
            val counters = perKind.getOrPut(case.kind) { IntArray(4) }
            counters[3]++
            when {
                result == case.expected -> { counters[0]++; ok++ }
                result == case.typed -> { counters[2]++; missed++ }
                else -> { counters[1]++; wrong++ }
            }
        }
        for ((kind, c) in perKind) {
            say(
                String.format(
                    Locale.ROOT,
                    "  %-26s bonnes %5.1f %% | mauvaises %5.1f %% | non corrigées %5.1f %% (%d)",
                    kind, percent(c[0], c[3]), percent(c[1], c[3]), percent(c[2], c[3]), c[3],
                ),
            )
        }
        val falsePositives = workload.correct.count { corrector.correct(it) != null }

        // Latences.
        val errorNs = timeAll(corrector, workload.errors.map { it.typed })
        val correctNs = timeAll(corrector, workload.correct)
        val gibberishNs = timeAll(corrector, workload.gibberish)
        val maxNs = maxOf(errorNs.lastOrNull() ?: 0L, correctNs.lastOrNull() ?: 0L, gibberishNs.lastOrNull() ?: 0L)

        val total = workload.errors.size
        return Row(
            name = name,
            okPercent = percent(ok, total),
            wrongPercent = percent(wrong, total),
            missedPercent = percent(missed, total),
            falsePositivePercent = percent(falsePositives, workload.correct.size),
            p50ErrorUs = percentileUs(errorNs, 0.50),
            p95ErrorUs = percentileUs(errorNs, 0.95),
            p95CorrectUs = percentileUs(correctNs, 0.95),
            p95GibberishUs = percentileUs(gibberishNs, 0.95),
            maxUs = maxNs / 1000.0,
        )
    }

    private fun percent(part: Int, total: Int): Double = if (total == 0) 0.0 else 100.0 * part / total

    /** Durées (ns) de [inputs], triées. */
    private fun timeAll(corrector: Corrector, inputs: List<String>): LongArray {
        val durations = LongArray(inputs.size)
        for (i in inputs.indices) {
            val start = System.nanoTime()
            corrector.correct(inputs[i])
            durations[i] = System.nanoTime() - start
        }
        durations.sort()
        return durations
    }

    private fun percentileUs(sortedNs: LongArray, fraction: Double): Double =
        if (sortedNs.isEmpty()) 0.0 else sortedNs[((sortedNs.size - 1) * fraction).toInt()] / 1000.0

    /** Jeu d'essai reproductible (graine fixe) : fautes réalistes générées depuis les mots les plus fréquents. */
    private fun buildWorkload(entries: Map<String, Long>, proximity: KeyProximity): Workload {
        val random = Random(42)
        val pool = entries.entries.asSequence()
            .filter { it.value >= CANDIDATE_MIN_FREQUENCY }
            .sortedByDescending { it.value }
            .take(30_000)
            .map { it.key }
            .toList()
            .shuffled(random)

        fun neighbors(c: Char): List<Char> = ('a'..'z').filter { proximity.areNeighbors(c, it) }

        val errors = ArrayList<Case>()
        fun add(kind: String, words: List<String>, allowKnownTyped: Boolean = false, make: (String) -> String?) {
            var count = 0
            for (word in words) {
                if (count >= CASES_PER_KIND) break
                val typed = make(word) ?: continue
                if (typed == word || (!allowKnownTyped && typed in entries)) continue
                errors += Case(kind, typed, word)
                count++
            }
        }

        val plain = pool.filter { PLAIN.matches(it) }
        add("touche voisine", plain) { word ->
            val i = random.nextInt(1, word.length)
            val near = neighbors(word[i])
            if (near.isEmpty()) null else word.substring(0, i) + near.random(random) + word.substring(i + 1)
        }
        add("inversion de deux lettres", plain) { word ->
            val i = random.nextInt(1, word.length - 1)
            if (word[i] == word[i + 1]) null else {
                val chars = word.toCharArray()
                chars[i] = word[i + 1]
                chars[i + 1] = word[i]
                String(chars)
            }
        }
        add("lettre manquante", plain) { word ->
            val i = random.nextInt(1, word.length)
            word.removeRange(i, i + 1)
        }
        add("lettre en trop (touche voisine)", plain) { word ->
            val i = random.nextInt(1, word.length)
            val near = neighbors(word[i])
            if (near.isEmpty()) null else word.substring(0, i + 1) + near.random(random) + word.substring(i + 1)
        }
        add("lettre doublée", plain) { word ->
            val i = random.nextInt(1, word.length)
            word.substring(0, i + 1) + word[i] + word.substring(i + 1)
        }
        add("accent oublié", pool.filter { ACCENTED.matches(it) && it.any { c -> c in ACCENT_CHARS } }, allowKnownTyped = true) { word ->
            Dictionary.foldAccents(word)
        }
        add("apostrophe oubliée", pool.filter { WITH_APOSTROPHE.matches(it) }, allowKnownTyped = true) { word ->
            word.replace("'", "")
        }

        val gibberish = ArrayList<String>()
        while (gibberish.size < GIBBERISH_WORDS) {
            val length = random.nextInt(5, 11)
            val word = String(CharArray(length) { 'a' + random.nextInt(26) })
            if (word !in entries) gibberish += word
        }
        return Workload(errors, pool.take(CORRECT_WORDS), gibberish)
    }
}
