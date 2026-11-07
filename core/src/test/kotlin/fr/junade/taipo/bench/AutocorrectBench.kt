package fr.junade.taipo.bench

import fr.junade.taipo.dictionary.AccentPolicy
import fr.junade.taipo.dictionary.CorrectionConfidence
import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.ContextualCorrection
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

    /**
     * Ce qui change d'une langue à l'autre dans le banc : dictionnaire, seuils, clavier, décision de déclenchement, correction
     * de contexte et jeux de phrases. [FRENCH] est le profil historique (la référence `baseline.txt` du français).
     */
    class Profile(
        val language: KeyboardLanguage,
        val proximity: KeyProximity,
        val inflections: InflectionRules,
        /** Fréquence minimale pour être proposé (`DictionaryLoader.candidateMinFrequency`). */
        val candidateMinFrequency: Long,
        val confidence: CorrectionConfidence,
        /** Correction de contexte appliquée après celle du dictionnaire : texte corrigé, ou null. */
        val context: (text: String, frequencyOf: (String) -> Long) -> String?,
        val sentencesPath: String,
        val casesPath: String,
        val baselinePath: String,
        /** Politique des oublis d'accent (`DictionaryLoader.accentPolicyOf`). */
        val accentPolicy: AccentPolicy = AccentPolicy.FRENCH,
    ) {
        val code: String get() = language.tag
    }

    private val QWERTY: KeyProximity = run {
        fun row(letters: String) = letters.map { KeyProximity.KeyBox(it, 1f) }
        KeyProximity.fromRows(
            listOf(
                row("qwertyuiop"),
                listOf(KeyProximity.KeyBox(null, 0.5f)) + row("asdfghjkl") + listOf(KeyProximity.KeyBox(null, 0.5f)),
                listOf(KeyProximity.KeyBox(null, 1.5f)) + row("zxcvbnm") + listOf(KeyProximity.KeyBox(null, 1.5f)),
            ),
        )
    }

    val FRENCH = Profile(
        KeyboardLanguage.FR, AZERTY, InflectionRules.FRENCH, 100L, CorrectionConfidence.STANDARD,
        { text, f -> FrenchContextCorrector.correct(text, textMayBeTruncated = false, allowRetro = true, frequencyOf = f) },
        "tools/autocorrect-bench/sentences-ok.txt", "tools/autocorrect-bench/context-cases.tsv", "tools/autocorrect-bench/baseline.txt",
    )

    /** QWERTY espagnol : la touche Ñ ferme la rangée du milieu (10 lettres, sans marge). */
    private val QWERTY_N: KeyProximity = run {
        fun row(letters: String) = letters.map { KeyProximity.KeyBox(it, 1f) }
        KeyProximity.fromRows(
            listOf(
                row("qwertyuiop"),
                row("asdfghjklñ"),
                listOf(KeyProximity.KeyBox(null, 1.5f)) + row("zxcvbnm") + listOf(KeyProximity.KeyBox(null, 1.5f)),
            ),
        )
    }

    /**
     * Espagnol : QWERTY avec Ñ, seuil de proposition de `DictionaryLoader`, valeurs de départ de `CorrectionConfidence.SPANISH`
     * (non mesurées), contexte de `SpanishConfusions` et politique d'accents de l'espagnol.
     */
    val SPANISH = Profile(
        KeyboardLanguage.ES, QWERTY_N, InflectionRules.SPANISH, 183L, CorrectionConfidence.SPANISH,
        { text, f -> ContextualCorrection.correctWithoutHabits(KeyboardLanguage.ES, text, textMayBeTruncated = false, allowRetro = true, frequencyOf = f) },
        "tools/autocorrect-bench/es/sentences-ok.txt", "tools/autocorrect-bench/es/context-cases.tsv", "tools/autocorrect-bench/es/baseline.txt",
        AccentPolicy.SPANISH,
    )

    /** Anglais : clavier QWERTY, seuil de proposition de `DictionaryLoader`, décision et contexte de l'anglais. */
    val ENGLISH = Profile(
        KeyboardLanguage.EN, QWERTY, InflectionRules.ENGLISH, 149L, CorrectionConfidence.ENGLISH,
        { text, f -> ContextualCorrection.correctWithoutHabits(KeyboardLanguage.EN, text, textMayBeTruncated = false, allowRetro = true, frequencyOf = f) },
        "tools/autocorrect-bench/en/sentences-ok.txt", "tools/autocorrect-bench/en/context-cases.tsv", "tools/autocorrect-bench/en/baseline.txt",
    )

    /** L'anglais tel qu'avant ce travail (aucune décision de déclenchement, aucun contexte) : pour comparer. Aucune référence écrite. */
    val ENGLISH_LEGACY = Profile(
        KeyboardLanguage.EN, QWERTY, InflectionRules.ENGLISH, 149L, CorrectionConfidence.NONE, { _, _ -> null },
        ENGLISH.sentencesPath, ENGLISH.casesPath, "tools/autocorrect-bench/en/baseline-legacy.txt",
    )

    /** QWERTZ allemand : Y et Z échangés par rapport au QWERTY ; les umlauts (ä, ö, ü) et ß sont en appui long, hors de la grille. */
    private val QWERTZ: KeyProximity = run {
        fun row(letters: String) = letters.map { KeyProximity.KeyBox(it, 1f) }
        KeyProximity.fromRows(
            listOf(
                row("qwertzuiop"),
                listOf(KeyProximity.KeyBox(null, 0.5f)) + row("asdfghjkl") + listOf(KeyProximity.KeyBox(null, 0.5f)),
                listOf(KeyProximity.KeyBox(null, 1.5f)) + row("yxcvbnm") + listOf(KeyProximity.KeyBox(null, 1.5f)),
            ),
        )
    }

    /**
     * Allemand, **état d'avant tout calibrage** (phase 0 du plan du 06/11/2026) : seuil de proposition de `DictionaryLoader`,
     * aucune décision de déclenchement (`NONE`), politique d'accents d'origine, aucune règle de contexte. Les phases suivantes
     * changent ce profil et se jugent contre la référence que ce profil produit. La proximité est celle d'un QWERTZ simple
     * (les largeurs exactes de `KeyboardLayout` ne sont pas dans le module `core`).
     */
    val GERMAN = Profile(
        KeyboardLanguage.DE, QWERTZ, InflectionRules.GERMAN, 41L, CorrectionConfidence.NONE,
        { text, f -> ContextualCorrection.correctWithoutHabits(KeyboardLanguage.DE, text, textMayBeTruncated = false, allowRetro = true, frequencyOf = f) },
        "tools/autocorrect-bench/de/sentences-ok.txt", "tools/autocorrect-bench/de/context-cases.tsv", "tools/autocorrect-bench/de/baseline.txt",
        AccentPolicy.GERMAN,
    )

    private var profile: Profile = FRENCH

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

    /**
     * Le mot attendu est retrouvé. En allemand, la majuscule d'un nom (« haus » -> « Haus ») est voulue, pas une erreur :
     * la liste de noms la pose, et les listes de fréquences sont en minuscules.
     */
    private fun sameWord(result: String?, word: String): Boolean =
        result != null && (result == word || (profile.language == KeyboardLanguage.DE && result.lowercase() == word))

    fun loadDictionary(root: File, without: Set<String> = emptySet()): Dictionary {
        val frequencies = File(root, "dictionaries/${profile.code}.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, MIN_FREQUENCY)
        }
        val names = File(root, "dictionaries/names/${profile.code}.txt").takeIf { it.isFile }?.readLines(Charsets.UTF_8)
            ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
        // [without] : mots retirés des listes (mesure des mots justes inconnus du dictionnaire, voir [unknownWordMeasures]).
        val entries = if (without.isEmpty()) frequencies else frequencies.filterKeys { it !in without }
        return Dictionary.withFrequencies(
            entries, profile.inflections, profile.candidateMinFrequency, profile.proximity, properNames = names,
            confidence = profile.confidence, // la même décision que la langue de l'application (DictionaryLoader)
            accentPolicy = profile.accentPolicy,
        )
    }

    fun run(root: File, profile: Profile = FRENCH): Report {
        this.profile = profile
        val started = System.nanoTime()
        val dictionary = loadDictionary(root)
        val loadMillis = (System.nanoTime() - started) / 1_000_000
        val frequencies = File(root, "dictionaries/${profile.code}.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, profile.candidateMinFrequency)
        }
        val metrics = LinkedHashMap<String, Double>()
        val details = ArrayList<String>()
        val timings = ArrayList<Long>()

        typoMeasures(dictionary, frequencies, metrics, details, timings)
        validWordMeasures(dictionary, frequencies, metrics, details)
        longWordMeasures(dictionary, frequencies, metrics, details)
        unknownWordMeasures(root, frequencies, metrics, details)
        rareValidWordMeasures(root, dictionary, metrics, details)
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

        /** Allemand seulement : « ss » tapé pour « ß » (Straße -> Strasse). */
        ESZETT("ss-pour-eszett"),

        /** Allemand seulement : « ae / oe / ue » tapé pour « ä / ö / ü » (für -> fuer). */
        TRANSLIT("ae-oe-ue"),
    }

    /** Types de fautes propres à l'allemand : ignorés pour les autres langues, dont les références ne changent donc pas. */
    private val GERMAN_ONLY = setOf(Kind.ESZETT, Kind.TRANSLIT)

    private fun typoMeasures(
        dictionary: Dictionary,
        frequencies: Map<String, Long>,
        metrics: LinkedHashMap<String, Double>,
        details: MutableList<String>,
        timings: MutableList<Long>,
    ) {
        val plain = frequencies.keys.filter { w -> w.length >= 4 && w.all { it.isLetter() } }.sorted()
        val accented = plain.filter { Dictionary.foldAccents(it) != it }
        val eszett = plain.filter { 'ß' in it }
        val umlauts = plain.filter { w -> w.any { it in "äöü" } }
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
                if (kind in GERMAN_ONLY && profile.language != KeyboardLanguage.DE) continue
                val pool = when (kind) {
                    Kind.ACCENT -> accented
                    Kind.APOSTROPHE -> contractions
                    Kind.ESZETT -> eszett
                    Kind.TRANSLIT -> umlauts
                    else -> plain
                }
                if (pool.isEmpty()) continue // anglais : ni accents ni contractions oubliées sans pool
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
                        sameWord(result, word) -> { ok++; kindOk++ }
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
            val options = profile.proximity.neighborsOf(word[i]).filter { it.isLetter() }.sorted()
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
            val options = profile.proximity.neighborsOf(word[i]).filter { it.isLetter() }.sorted()
            if (options.isEmpty()) null else word.substring(0, i + 1) + options[random.nextInt(options.size)] + word.substring(i + 1)
        }
        Kind.ACCENT -> Dictionary.foldAccents(word)
        Kind.APOSTROPHE -> word.replace("'", "")
        Kind.ESZETT -> word.replace("ß", "ss")
        Kind.TRANSLIT -> word.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
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
                if (benchFold(result.lowercase()) == benchFold(word)) {
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
    // Mots longs à plusieurs erreurs (phase 3 : distance 3 des mots longs)
    // ------------------------------------------------------------------

    private const val LONG_SAMPLES = 300
    private const val LONG_MIN_LENGTH = 8
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

    /**
     * Mots d'au moins [LONG_MIN_LENGTH] lettres abîmés par 2 ou 3 erreurs aléatoires (touche voisine, lettre oubliée,
     * ajoutée, doublée ou quelconque), à la distance d'édition exacte voulue. Mesurés à part de `typos.*` : ces mesures ne
     * changent pas les totaux de référence. `ok` = le mot d'origine est rétabli, `wrong` = un autre mot le remplace.
     */
    private fun longWordMeasures(
        dictionary: Dictionary,
        frequencies: Map<String, Long>,
        metrics: LinkedHashMap<String, Double>,
        details: MutableList<String>,
    ) {
        val pool = frequencies.keys.filter { w -> w.length >= LONG_MIN_LENGTH && w.all { it.isLetter() } }.sorted()
        for (weighted in listOf(true, false)) {
            val setName = if (weighted) "courants" else "varies"
            val random = Random(SEED + if (weighted) 21 else 22)
            val sampler = Sampler(pool, frequencies, weighted, random)
            for ((edits, label) in listOf(2 to "deux", 3 to "trois")) {
                var ok = 0
                var wrong = 0
                var total = 0
                var attempts = 0
                val examples = ArrayList<String>()
                while (total < LONG_SAMPLES && attempts++ < LONG_SAMPLES * 50) {
                    val word = sampler.next()
                    var typo = word
                    repeat(edits) { typo = randomEdit(typo, random) }
                    if (typo == word || dictionary.contains(typo) || Dictionary.damerauLevenshtein(typo, word) != edits) continue
                    total++
                    val result = dictionary.correctionFor(typo)
                    when {
                        sameWord(result, word) -> ok++
                        result == null || result == typo -> {}
                        else -> {
                            wrong++
                            if (examples.size < 10) examples += "  [$setName/$label] $typo -> $result (attendu $word)"
                        }
                    }
                }
                metrics["longues.$setName.$label.ok"] = percent(ok, total)
                metrics["longues.$setName.$label.wrong"] = percent(wrong, total)
                details += "Mots longs à $edits erreurs ($setName), mauvaises corrections :"
                details += examples
            }
        }
    }

    private fun randomEdit(word: String, random: Random): String {
        val i = 1 + random.nextInt(word.length - 2)
        return when (random.nextInt(5)) {
            0 -> {
                val options = profile.proximity.neighborsOf(word[i]).filter { it.isLetter() }.sorted()
                if (options.isEmpty()) word else word.substring(0, i) + options[random.nextInt(options.size)] + word.substring(i + 1)
            }
            1 -> word.removeRange(i, i + 1)
            2 -> word.substring(0, i) + ALPHABET[random.nextInt(ALPHABET.length)] + word.substring(i)
            3 -> word.substring(0, i) + word[i] + word.substring(i)
            else -> word.substring(0, i) + ALPHABET[random.nextInt(ALPHABET.length)] + word.substring(i + 1)
        }
    }

    // ------------------------------------------------------------------
    // Mots justes inconnus du dictionnaire
    // ------------------------------------------------------------------

    private const val UNKNOWN_SAMPLES = 300

    /**
     * Prénoms, mots d'argot ou termes techniques : des mots justes que le dictionnaire ne connaît pas et que le moteur ne
     * doit pas remplacer par un autre mot. On les imite en retirant des mots courants des listes puis en les tapant :
     * c'est une borne haute pessimiste (un mot courant retiré laisse des voisins proches, ce qui n'est pas le cas d'un
     * vrai nom propre), à suivre en valeur relative d'une phase à l'autre. `faux` = le mot est remplacé par un autre.
     */
    private fun unknownWordMeasures(
        root: File,
        frequencies: Map<String, Long>,
        metrics: LinkedHashMap<String, Double>,
        details: MutableList<String>,
    ) {
        val pool = frequencies.keys.filter { w -> w.length >= 5 && w.all { it.isLetter() } }.sorted()
        val samples = LinkedHashMap<Boolean, List<String>>()
        for (weighted in listOf(true, false)) {
            val random = Random(SEED + if (weighted) 31 else 32)
            val sampler = Sampler(pool, frequencies, weighted, random)
            samples[weighted] = List(UNKNOWN_SAMPLES) { sampler.next() }
        }
        val dictionary = loadDictionary(root, without = samples.values.flatten().toSet())
        for ((weighted, words) in samples) {
            val setName = if (weighted) "courants" else "varies"
            val replaced = words.mapNotNull { word -> dictionary.correctionFor(word)?.takeIf { it != word }?.let { word to it } }
            metrics["inconnus.$setName.faux"] = percent(replaced.size, words.size)
            details += "Mots justes retirés du dictionnaire puis remplacés ($setName), exemples :"
            replaced.take(10).forEach { (word, result) -> details += "  $word -> $result" }
        }
    }

    // ------------------------------------------------------------------
    // Mots justes rares (allemand : composés)
    // ------------------------------------------------------------------

    /**
     * Mots justes (validés par des lexiques de clavier, voir `tools/de_bench_data.py`) que `de.txt` ne contient pas :
     * surtout des composés (« abendkleid »). Tapés tels quels, le moteur ne doit pas les remplacer. `faux` = remplacé par un
     * autre mot ; `restaure` = seul l'accent, le ß ou la majuscule est rétabli. Mesure présente seulement si le fichier
     * `valid-rare-words.txt` existe à côté des jeux de phrases de la langue.
     */
    private fun rareValidWordMeasures(
        root: File,
        dictionary: Dictionary,
        metrics: LinkedHashMap<String, Double>,
        details: MutableList<String>,
    ) {
        val file = File(File(root, profile.sentencesPath).parentFile, "valid-rare-words.txt").takeIf { it.isFile } ?: return
        val words = dataLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        var changed = 0
        var restored = 0
        val examples = ArrayList<String>()
        for (word in words) {
            val result = dictionary.correctionFor(word)?.takeIf { it != word } ?: continue
            if (benchFold(result.lowercase()) == benchFold(word)) {
                restored++
            } else {
                changed++
                if (examples.size < 40) examples += "  $word -> $result"
            }
        }
        metrics["composes.rares.faux"] = percent(changed, words.size)
        metrics["composes.rares.restaure"] = percent(restored, words.size)
        details += "Mots justes rares (absents de la liste) remplacés par un autre mot (${words.size} tirés), exemples :"
        details += examples
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
        val contextual = profile.context(result, dictionary::frequencyOf)
        if (contextual != null) result = contextual
        return result
    }

    private fun sentenceMeasures(root: File, dictionary: Dictionary, metrics: LinkedHashMap<String, Double>): List<String> {
        val sentences = dataLines(File(root, profile.sentencesPath))
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
        val cases = dataLines(File(root, profile.casesPath)).map { it.split('\t') }
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

    /** Repli de comparaison du banc : accents ôtés, et « ß » = « ss » (sans effet hors allemand). */
    private fun benchFold(word: String): String = Dictionary.foldAccents(word.replace("ß", "ss"))

    private fun percent(part: Int, total: Int): Double = if (total == 0) 0.0 else Math.round(part * 10_000.0 / total) / 100.0

    // ------------------------------------------------------------------
    // Référence (baseline)
    // ------------------------------------------------------------------

    fun writeBaseline(root: File, report: Report, profile: Profile = FRENCH) {
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
        File(root, profile.baselinePath).writeText(text, Charsets.UTF_8)
    }

    class Baseline(val metrics: Map<String, Double>, val failingCases: Set<String>, val changedSentences: Set<String>)

    fun readBaseline(root: File, profile: Profile = FRENCH): Baseline? {
        val file = File(root, profile.baselinePath).takeIf { it.isFile } ?: return null
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

/** `main [--lang=en|es|de] [--write-baseline]` : affiche le rapport du banc (depuis la racine du projet ou un sous-dossier). */
fun main(args: Array<String>) {
    val root = AutocorrectBench.findRoot(File(System.getProperty("user.dir")))
    val profile = when {
        "--lang=en-legacy" in args -> AutocorrectBench.ENGLISH_LEGACY
        "--lang=en" in args -> AutocorrectBench.ENGLISH
        "--lang=es" in args -> AutocorrectBench.SPANISH
        "--lang=de" in args -> AutocorrectBench.GERMAN
        else -> AutocorrectBench.FRENCH
    }
    val report = AutocorrectBench.run(root, profile)
    println(report.render())
    if ("--write-baseline" in args) {
        AutocorrectBench.writeBaseline(root, report, profile)
        println("Référence écrite dans ${profile.baselinePath}")
    }
}
