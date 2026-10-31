package fr.junade.taipo.bench

import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.dictionary.FrenchContextCorrector
import fr.junade.taipo.dictionary.InflectionRules
import fr.junade.taipo.dictionary.KeyProximity
import fr.junade.taipo.suggestion.WordText
import java.io.File
import java.util.Random

/**
 * Banc de mesure de l'autocorrection française (phase 0 de la refonte de l'autocorrecteur, 30/10/2026).
 *
 * Il charge le vrai dictionnaire (`dictionaries/fr.txt`, mêmes seuils que `DictionaryLoader`) et mesure, de façon
 * reproductible (graine fixe) :
 *  - **fautes de frappe** tirées au hasard (touche voisine, lettre oubliée, doublée, ajoutée, inversion, accent oublié,
 *    apostrophe oubliée) : bien corrigées / non corrigées / mal corrigées ;
 *  - **fausses corrections** : mots justes que le moteur change à tort (le pire défaut d'un clavier) ;
 *  - **phrases justes** (`tools/autocorrect-bench/sentences-ok.txt`) : aucune ne doit changer ;
 *  - **cas de contexte** (`tools/autocorrect-bench/context-cases.tsv`) : phrase fautive -> phrase attendue, frappée mot par mot
 *    comme au clavier (correction du dictionnaire puis correction de contexte français, dans l'ordre de l'application) ;
 *  - **temps** de `correctionFor` (relatif : cette machine n'est pas un Pixel).
 *
 * Logique pure, sans JUnit : lancé par `main` (voir docs/autocorrection-bench.md) ou par `AutocorrectBaselineTest`.
 * Habitudes et dictionnaire personnel sont volontairement absents : on mesure le moteur général.
 */
object AutocorrectBench {

    private const val SEED = 20_261_030L
    private const val SAMPLES_PER_KIND = 1_000
    private const val VALID_SAMPLES = 5_000
    private const val MIN_FREQUENCY = 5L
    private const val CANDIDATE_MIN_FREQUENCY = 100L

    /** Proximité des touches AZERTY de Taipo (mêmes rangées et mêmes poids que KeyboardLayout et PruneDictionaryTypos). */
    private val AZERTY: KeyProximity = run {
        fun row(letters: String) = letters.map { KeyProximity.KeyBox(it, 1f) }
        KeyProximity.fromRows(
            listOf(
                row("azertyuiop"),
                row("qsdfghjklm"),
                listOf(KeyProximity.KeyBox(null, 1.4f)) + row("wxcvbn") +
                    listOf(KeyProximity.KeyBox('\'', 1f), KeyProximity.KeyBox(null, 1.6f)),
            ),
        )
    }

    class Report(
        /** Mesures en pour cent (« typos.courants.ok » = 93,4), dans l'ordre d'affichage. */
        val metrics: LinkedHashMap<String, Double>,
        /** Cas de contexte qui échouent : « categorie<TAB>texte tapé ». */
        val failingCases: List<String>,
        /** Phrases justes que le moteur change. */
        val changedSentences: List<String>,
        /** Lignes de détail (exemples d'erreurs), pour la lecture humaine seulement. */
        val details: List<String>,
        /** Temps de correctionFor en microsecondes : p50, p95, p99, max. */
        val timingsMicros: List<Long>,
        val loadMillis: Long,
    ) {
        fun render(): String = buildString {
            appendLine("== Mesures (en %) ==")
            metrics.forEach { (key, value) -> appendLine("%-34s %6.2f".format(key, value)) }
            appendLine()
            appendLine("== Temps de correctionFor (µs, cette machine) : p50=%d p95=%d p99=%d max=%d ; chargement du dictionnaire %d ms ==".format(
                timingsMicros[0], timingsMicros[1], timingsMicros[2], timingsMicros[3], loadMillis,
            ))
            appendLine()
            appendLine("== Phrases justes changées à tort (${changedSentences.size}) ==")
            changedSentences.forEach { appendLine(it) }
            appendLine()
            appendLine("== Cas de contexte en échec (${failingCases.size}) ==")
            failingCases.forEach { appendLine(it.replace("\t", " | ")) }
            appendLine()
            appendLine("== Détails ==")
            details.forEach { appendLine(it) }
        }
    }

    fun findRoot(start: File): File {
        var dir: File? = start.absoluteFile
        while (dir != null) {
            if (File(dir, "dictionaries/fr.txt").isFile && File(dir, "tools/autocorrect-bench").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Racine du projet introuvable depuis $start (dictionaries/fr.txt et tools/autocorrect-bench attendus)")
    }

    fun loadDictionary(root: File): Dictionary {
        val frequencies = File(root, "dictionaries/fr.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, MIN_FREQUENCY)
        }
        val names = File(root, "dictionaries/names/fr.txt").takeIf { it.isFile }?.readLines(Charsets.UTF_8)
            ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
        return Dictionary.withFrequencies(frequencies, InflectionRules.FRENCH, CANDIDATE_MIN_FREQUENCY, AZERTY, properNames = names)
    }

    fun run(root: File): Report {
        val started = System.nanoTime()
        val dictionary = loadDictionary(root)
        val loadMillis = (System.nanoTime() - started) / 1_000_000
        val frequencies = File(root, "dictionaries/fr.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, CANDIDATE_MIN_FREQUENCY)
        }
        val metrics = LinkedHashMap<String, Double>()
        val details = ArrayList<String>()
        val timings = ArrayList<Long>()

        typoMeasures(dictionary, frequencies, metrics, details, timings)
        validWordMeasures(dictionary, frequencies, metrics, details)
        val changedSentences = sentenceMeasures(root, dictionary, metrics)
        val failingCases = contextMeasures(root, dictionary, metrics, details)

        timings.sort()
        fun percentile(p: Double) = if (timings.isEmpty()) 0L else timings[minOf(timings.size - 1, (timings.size * p).toInt())]
        val timingList = listOf(percentile(0.50), percentile(0.95), percentile(0.99), timings.lastOrNull() ?: 0L)
        return Report(metrics, failingCases, changedSentences, details, timingList, loadMillis)
    }

    // ------------------------------------------------------------------
    // Fautes de frappe tirées au hasard
    // ------------------------------------------------------------------

    private enum class Kind(val label: String) {
        NEIGHBOR("voisine"), OMISSION("oubli"), DOUBLE("doublon"), SWAP("inversion"), INSERT("ajout"),
        ACCENT("accent"), APOSTROPHE("apostrophe"),
    }

    private fun typoMeasures(
        dictionary: Dictionary,
        frequencies: Map<String, Long>,
        metrics: LinkedHashMap<String, Double>,
        details: MutableList<String>,
        timings: MutableList<Long>,
    ) {
        val plain = frequencies.keys.filter { w -> w.length >= 4 && w.all { it.isLetter() } }.sorted()
        val accented = plain.filter { Dictionary.foldAccents(it) != it }
        val contractions = frequencies.keys.filter { '\'' in it && it.length >= 4 && it.count { c -> c == '\'' } == 1 }.sorted()
        for (weighted in listOf(true, false)) {
            val setName = if (weighted) "courants" else "varies"
            val random = Random(SEED + if (weighted) 1 else 2)
            var ok = 0
            var missed = 0
            var wrong = 0
            var onValidWord = 0
            val wrongExamples = ArrayList<String>()
            for (kind in Kind.values()) {
                val pool = when (kind) {
                    Kind.ACCENT -> accented
                    Kind.APOSTROPHE -> contractions
                    else -> plain
                }
                val sampler = Sampler(pool, frequencies, weighted, random)
                var kindOk = 0
                var kindTotal = 0
                var attempts = 0
                while (kindTotal < SAMPLES_PER_KIND && attempts++ < SAMPLES_PER_KIND * 20) {
                    val word = sampler.next()
                    val typo = makeTypo(word, kind, random) ?: continue
                    if (typo == word) continue
                    if (dictionary.contains(typo)) { onValidWord++; continue }
                    kindTotal++
                    val t0 = System.nanoTime()
                    val result = dictionary.correctionFor(typo)
                    timings += (System.nanoTime() - t0) / 1_000
                    when {
                        result == word -> { ok++; kindOk++ }
                        result == null || result == typo -> missed++
                        else -> {
                            wrong++
                            if (wrongExamples.size < 25) wrongExamples += "  [$setName/${kind.label}] $typo -> $result (attendu $word)"
                        }
                    }
                }
                metrics["typos.$setName.${kind.label}.ok"] = percent(kindOk, kindTotal)
            }
            val total = ok + missed + wrong
            metrics["typos.$setName.ok"] = percent(ok, total)
            metrics["typos.$setName.missed"] = percent(missed, total)
            metrics["typos.$setName.wrong"] = percent(wrong, total)
            metrics["typos.$setName.surMotValide"] = percent(onValidWord, total + onValidWord)
            details += "Fautes tombant sur un mot valide ($setName) : $onValidWord, non comptées dans ok/missed/wrong (le dictionnaire ne peut pas les voir)."
            details += "Exemples de mauvaises corrections ($setName) :"
            details += wrongExamples
        }
    }

    private class Sampler(
        private val pool: List<String>,
        frequencies: Map<String, Long>,
        private val weighted: Boolean,
        private val random: Random,
    ) {
        private val cumulative = LongArray(pool.size)

        init {
            var sum = 0L
            for (i in pool.indices) { sum += frequencies.getValue(pool[i]); cumulative[i] = sum }
        }

        fun next(): String {
            if (!weighted) return pool[random.nextInt(pool.size)]
            val target = (random.nextDouble() * cumulative.last()).toLong()
            var lo = 0
            var hi = cumulative.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (cumulative[mid] <= target) lo = mid + 1 else hi = mid
            }
            return pool[lo]
        }
    }

    private fun makeTypo(word: String, kind: Kind, random: Random): String? = when (kind) {
        Kind.NEIGHBOR -> {
            val i = random.nextInt(word.length)
            val options = AZERTY.neighborsOf(word[i]).filter { it.isLetter() }.sorted()
            if (options.isEmpty()) null else word.substring(0, i) + options[random.nextInt(options.size)] + word.substring(i + 1)
        }
        Kind.OMISSION -> {
            val i = 1 + random.nextInt(word.length - 1)
            word.removeRange(i, i + 1)
        }
        Kind.DOUBLE -> {
            val i = random.nextInt(word.length)
            if (i + 1 < word.length && word[i] == word[i + 1]) null else word.substring(0, i + 1) + word[i] + word.substring(i + 1)
        }
        Kind.SWAP -> {
            val i = 1 + random.nextInt(word.length - 2)
            if (word[i] == word[i + 1]) null else word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
        }
        Kind.INSERT -> {
            val i = random.nextInt(word.length)
            val options = AZERTY.neighborsOf(word[i]).filter { it.isLetter() }.sorted()
            if (options.isEmpty()) null else word.substring(0, i + 1) + options[random.nextInt(options.size)] + word.substring(i + 1)
        }
        Kind.ACCENT -> Dictionary.foldAccents(word)
        Kind.APOSTROPHE -> word.replace("'", "")
    }

    // ------------------------------------------------------------------
    // Mots justes
    // ------------------------------------------------------------------

    private fun validWordMeasures(
        dictionary: Dictionary,
        frequencies: Map<String, Long>,
        metrics: LinkedHashMap<String, Double>,
        details: MutableList<String>,
    ) {
        val pool = frequencies.keys.filter { it.length >= 2 && '\'' !in it }.sorted()
        for (weighted in listOf(true, false)) {
            val setName = if (weighted) "courants" else "varies"
            val random = Random(SEED + if (weighted) 11 else 12)
            val sampler = Sampler(pool, frequencies, weighted, random)
            var changed = 0
            var restored = 0
            val offenders = LinkedHashMap<String, String>()
            repeat(VALID_SAMPLES) {
                val word = sampler.next()
                val result = dictionary.correctionFor(word)
                if (result == null || result == word) return@repeat
                // Accent rétabli ou majuscule d'un prénom (« ca » -> « ça », « kevin » -> « Kévin ») : voulu, pas une erreur.
                if (Dictionary.foldAccents(result.lowercase()) == Dictionary.foldAccents(word)) {
                    restored++
                } else {
                    changed++
                    if (offenders.size < 25) offenders.putIfAbsent(word, result)
                }
            }
            metrics["valides.$setName.faux"] = percent(changed, VALID_SAMPLES)
            metrics["valides.$setName.restaure"] = percent(restored, VALID_SAMPLES)
            details += "Mots justes remplacés par un autre mot ($setName), exemples :"
            offenders.forEach { (word, result) -> details += "  $word -> $result" }
        }
    }

    // ------------------------------------------------------------------
    // Phrases
    // ------------------------------------------------------------------

    /**
     * Frappe [sentence] caractère par caractère. L'autocorrection se déclenche quand un caractère qui n'est pas dans un mot
     * (espace, ponctuation) va être tapé, et à la fin de la phrase : même ordre que `SuggestionController.autocorrectionFor`
     * (correction du dictionnaire, puis correction de contexte français), sans habitudes ni dictionnaire personnel.
     */
    fun typeSentence(dictionary: Dictionary, sentence: String): String {
        var text = ""
        for (c in sentence) {
            if (!WordText.isWordChar(c)) text = autocorrect(dictionary, text)
            text += c
        }
        return autocorrect(dictionary, text)
    }

    private fun autocorrect(dictionary: Dictionary, textBeforeCursor: String): String {
        val word = WordText.trailingWord(textBeforeCursor)
        if (word.isEmpty()) return textBeforeCursor
        val fix = dictionary.correctionFor(word)?.takeIf { it != word }
        var result = if (fix != null) textBeforeCursor.dropLast(word.length) + fix else textBeforeCursor
        val contextual = FrenchContextCorrector.correct(
            text = result,
            textMayBeTruncated = false,
            allowRetro = true,
            frequencyOf = dictionary::frequencyOf,
        )
        if (contextual != null) result = contextual
        return result
    }

    private fun sentenceMeasures(root: File, dictionary: Dictionary, metrics: LinkedHashMap<String, Double>): List<String> {
        val sentences = dataLines(File(root, "tools/autocorrect-bench/sentences-ok.txt"))
        val changed = sentences.filter { typeSentence(dictionary, it) != it }.map { "$it -> ${typeSentence(dictionary, it)}" }
        metrics["phrases.justes.changees"] = percent(changed.size, sentences.size)
        return changed
    }

    private fun contextMeasures(
        root: File,
        dictionary: Dictionary,
        metrics: LinkedHashMap<String, Double>,
        details: MutableList<String>,
    ): List<String> {
        val cases = dataLines(File(root, "tools/autocorrect-bench/context-cases.tsv")).map { it.split('\t') }
        val failing = ArrayList<String>()
        val perCategory = LinkedHashMap<String, IntArray>() // [réussis, total]
        details += "Cas de contexte en échec (résultat obtenu) :"
        for (parts in cases) {
            check(parts.size == 3) { "ligne invalide dans context-cases.tsv : $parts" }
            val (category, typed, expected) = parts
            val got = typeSentence(dictionary, typed)
            val counts = perCategory.getOrPut(category) { IntArray(2) }
            counts[1]++
            if (got == expected) counts[0]++ else {
                failing += "$category\t$typed"
                details += "  [$category] $typed -> $got (attendu $expected)"
            }
        }
        var passed = 0
        var total = 0
        perCategory.forEach { (category, counts) ->
            metrics["contexte.$category.ok"] = percent(counts[0], counts[1])
            passed += counts[0]
            total += counts[1]
        }
        metrics["contexte.total.ok"] = percent(passed, total)
        return failing
    }

    private fun dataLines(file: File): List<String> =
        file.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }

    private fun percent(part: Int, total: Int): Double = if (total == 0) 0.0 else Math.round(part * 10_000.0 / total) / 100.0

    // ------------------------------------------------------------------
    // Référence (baseline)
    // ------------------------------------------------------------------

    private const val BASELINE_PATH = "tools/autocorrect-bench/baseline.txt"

    fun writeBaseline(root: File, report: Report) {
        val text = buildString {
            appendLine("# Référence du banc d'autocorrection (docs/autocorrection-bench.md). Générée par `main --write-baseline`.")
            appendLine("# À régénérer volontairement quand une phase de la refonte change le comportement, jamais pour masquer une régression.")
            appendLine("[metrics]")
            report.metrics.forEach { (key, value) -> appendLine("$key=$value") }
            appendLine("[failing-cases]")
            report.failingCases.forEach { appendLine(it) }
            appendLine("[changed-sentences]")
            report.changedSentences.forEach { appendLine(it) }
        }
        File(root, BASELINE_PATH).writeText(text, Charsets.UTF_8)
    }

    class Baseline(val metrics: Map<String, Double>, val failingCases: Set<String>, val changedSentences: Set<String>)

    fun readBaseline(root: File): Baseline? {
        val file = File(root, BASELINE_PATH).takeIf { it.isFile } ?: return null
        val metrics = LinkedHashMap<String, Double>()
        val cases = LinkedHashSet<String>()
        val sentences = LinkedHashSet<String>()
        var section = ""
        for (line in file.readLines(Charsets.UTF_8)) {
            when {
                line.startsWith("#") || line.isBlank() -> {}
                line.startsWith("[") -> section = line
                section == "[metrics]" -> line.split('=').let { metrics[it[0]] = it[1].toDouble() }
                section == "[failing-cases]" -> cases += line
                section == "[changed-sentences]" -> sentences += line
            }
        }
        return Baseline(metrics, cases, sentences)
    }
}

/** `main [--write-baseline]` : affiche le rapport du banc (depuis la racine du projet ou un sous-dossier). */
fun main(args: Array<String>) {
    val root = AutocorrectBench.findRoot(File(System.getProperty("user.dir")))
    val report = AutocorrectBench.run(root)
    println(report.render())
    if ("--write-baseline" in args) {
        AutocorrectBench.writeBaseline(root, report)
        println("Référence écrite dans tools/autocorrect-bench/baseline.txt")
    }
}
