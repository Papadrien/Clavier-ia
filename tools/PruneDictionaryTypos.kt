import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.dictionary.KeyProximity
import java.io.File

/**
 * Nettoyage de la liste française (dictionaries/fr.txt) : retire les fautes de frappe du corpus
 * (OpenSubtitles). Un mot est retiré s'il remplit TOUTES ces conditions :
 *  - il est rare (moins de [MAX_TYPO_FREQUENCY] + 1 occurrences : un tel mot n'est jamais proposé, il ne fait que
 *    valider une faute) et compte au moins [MIN_LENGTH] lettres ;
 *  - il est à UNE modification d'un mot au moins [DOMINANCE] fois plus fréquent (« oeux » -> « peux »,
 *    « eet » -> « est ») : suppression, remplacement ou insertion d'une lettre, ou inversion de deux lettres voisines ;
 *  - cette modification est une faute plausible au clavier AZERTY de Taipo (touche voisine, lettre oubliée, lettre
 *    doublée ou effleurée, inversion : coût <= 2 dans [KeyProximity.editCost]), ce qui épargne les vrais mots rares
 *    (« hêtre », « jurais », « coupa ») ;
 *  - elle ne se limite pas à un accent (« commenté » / « commente » : les accents sont traités par le dictionnaire).
 * Une fois retirés, ces mots sont inconnus : l'autocorrection les corrige au lieu de les laisser passer.
 *
 * Utilisation (hors build Gradle), depuis la racine du projet :
 *   kotlinc core/src/main/kotlin tools/PruneDictionaryTypos.kt -include-runtime -d prune.jar
 *   java -jar prune.jar fr.txt fr.pruned.txt [--list]
 * `--list` affiche aussi les mots retirés de fréquence >= 30 (les plus fréquents, à relire).
 * Dernière exécution : 05/10/2026 -> 10/10/2026 (voir dictionaries/SOURCES.txt).
 */
private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzéèêàâùûôîïçëöüœæ'-"
private const val MAX_TYPO_FREQUENCY = 99L
private const val DOMINANCE = 1000L
private const val MIN_LENGTH = 3

/**
 * Fautes évidentes de la tranche 100 à 1 000 occurrences, retirées une à une : à cette fréquence, les critères
 * automatiques ne distinguent plus les fautes des vrais mots rares (« veuf », « suer », « voué », « pus »).
 */
private val EXTRA_TYPOS = setOf(
    "quo", "pouquoi", "plis", "troi", "notres", "votres", "maitenant", "bein", "estce", "pourait", "touts", "ajussi",
    "vosu", "ètait", "içi", "ést", "qué", "voss", "ête", "dûr",
)

/** Proximité des touches de la disposition AZERTY de Taipo (mêmes rangées et mêmes poids que KeyboardLayout). */
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

private fun isPlausibleSlip(word: String, neighbor: String): Boolean =
    Dictionary.foldAccents(word) != Dictionary.foldAccents(neighbor) && AZERTY.editCost(word, neighbor) <= 2

private fun neighbors(word: String): Sequence<String> = sequence {
    for (i in word.indices) yield(word.removeRange(i, i + 1))
    for (i in word.indices) for (c in ALPHABET) if (c != word[i]) yield(word.substring(0, i) + c + word.substring(i + 1))
    for (i in 0..word.length) for (c in ALPHABET) yield(word.substring(0, i) + c + word.substring(i))
    for (i in 0 until word.length - 1) {
        if (word[i] != word[i + 1]) yield(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2))
    }
}

fun main(args: Array<String>) {
    val lines = File(args[0]).readLines(Charsets.UTF_8)
    val frequencies = HashMap<String, Long>(lines.size * 2)
    for (line in lines) {
        val space = line.lastIndexOf(' ')
        if (space > 0) line.substring(space + 1).toLongOrNull()?.let { frequencies[line.substring(0, space)] = it }
    }
    val typos = HashSet<String>()
    for ((word, frequency) in frequencies) {
        if (frequency > MAX_TYPO_FREQUENCY || word.length < MIN_LENGTH) continue
        if (neighbors(word).any { (frequencies[it] ?: 0L) >= frequency * DOMINANCE && isPlausibleSlip(word, it) }) typos += word
    }
    val kept = lines.filter { line ->
        val space = line.lastIndexOf(' ')
        space <= 0 || line.substring(0, space).let { it !in typos && it !in EXTRA_TYPOS }
    }
    File(args[1]).writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    println("${lines.size} lignes lues, ${typos.size} fautes retirées automatiquement, ${lines.size - kept.size} lignes retirées en tout, ${kept.size} lignes écrites")
    if ("--list" in args) {
        typos.filter { frequencies.getValue(it) >= 30 }.sortedByDescending { frequencies.getValue(it) }
            .forEach { println("$it ${frequencies.getValue(it)}") }
    }
}
