package fr.junade.taipo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.junade.taipo.dictionary.Autocorrection
import fr.junade.taipo.dictionary.DictionaryLoader
import fr.junade.taipo.dictionary.LatencyHistogram
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 : latence du moteur d'autocorrection (`Autocorrection.correctedText`, dictionnaire + contexte français) sur le
 * vrai appareil et le vrai dictionnaire français, hors interface. C'est la part du temps de frappe qui dépend du moteur.
 *
 * Quatre corpus fixes (aucun hasard, donc comparables d'un lancement à l'autre) : mots justes, fautes de frappe, mots longs
 * inconnus (qui déclenchent la recherche à trois erreurs, le chemin le plus coûteux) et phrases tapées mot par mot. Après un
 * échauffement (compilation JIT), chaque correction est chronométrée ; le rapport (nombre, p50, p95, p99, max, durées
 * d'une image ou plus) est écrit dans logcat, balise `AutocorrectionLatency`.
 *
 * Aucun budget chiffré n'est imposé (décision antérieure, voir docs/performance.md) : le test ne vérifie que ce qu'il a mesuré.
 * Les chiffres se comparent d'un lancement à l'autre, sur le même appareil dans les mêmes conditions. Le test tourne sur le
 * build debug (ni R8 ni profil) : ses durées sont plus hautes que celles d'une release ; pour la release, voir les sections
 * de trace `Taipo.autocorrection` et `Taipo.suggestions` du module `macrobenchmark`.
 *
 * Non exécuté depuis l'environnement de développement (ni appareil ni compilateur) :
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=fr.junade.taipo.AutocorrectionLatencyTest`
 */
@RunWith(AndroidJUnit4::class)
class AutocorrectionLatencyTest {

    private class Corpus(val label: String, val texts: List<String>)

    private val validWords = listOf(
        "bonjour", "maison", "travail", "ordinateur", "demain", "toujours", "peut-être", "aujourd'hui", "français", "école",
        "problème", "difficile", "beaucoup", "pourquoi", "maintenant", "développement", "rendez-vous", "j'ai", "c'est", "l'ordinateur",
    )

    private val typos = listOf(
        "bonjor", "mrci", "aujourdhui", "pourqoi", "tinquiete", "jetais", "maintenent", "developement", "apelle", "beacoup",
        "ecole", "probleme", "dificile", "tavail", "ordinatuer", "demian", "toujous", "frnaçais", "cest", "jai",
        "rendezvous", "peutetre", "lordinateur", "acceuil", "adresee",
    )

    private val longUnknown = listOf(
        "anticonstitutionnellemnt", "dezoptimisassionnel", "kubernetesss", "xylophoniquement", "reconnaisssance",
        "gouvernementalemnt", "photosynthesee", "antidisestablishmentarian", "hippopotomonstrosesquipedaliophobe",
    )

    private val sentences = listOf(
        "Ils on fini leur travail",
        "Il va a la plage",
        "je pence que sa va bien",
        "bonjor tout le monde",
        "ce soir on mange chez moi",
        "Elle a manger une pomme",
        "tinquiete pas ca va aller",
        "jetais en retard hier soir",
        "Les enfants on joué dehors",
        "Il ce lève tôt le matin",
    )

    /** Chaque fin de mot de [sentence] : le texte avant le curseur au moment où l'espace est tapée. */
    private fun wordEnds(sentence: String): List<String> {
        val ends = ArrayList<String>()
        var index = 0
        for (word in sentence.split(' ')) {
            index += word.length
            ends += sentence.substring(0, index)
            index++ // l'espace qui suit
        }
        return ends
    }

    @Test
    fun measureAutocorrectionLatency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionary = DictionaryLoader.forLanguage(context, KeyboardLanguage.FR)
        // Un dictionnaire vide (listes introuvables) ne mesurerait rien d'utile.
        assertTrue("dictionnaire français non chargé", dictionary.frequencyOf("bonjour") > 0L)

        val corpora = listOf(
            Corpus("mots justes", validWords),
            Corpus("fautes de frappe", typos),
            Corpus("mots longs inconnus (recherche a 3 erreurs)", longUnknown),
            Corpus("phrases mot par mot (dictionnaire + contexte)", sentences.flatMap { wordEnds(it) }),
        )

        fun correct(text: String): String =
            Autocorrection.correctedText(
                textBeforeCursor = text,
                textMayBeTruncated = false,
                allowRetro = true,
                dictionary = dictionary,
                personalWords = emptyList(),
                contextual = true,
                habitsFor = { null },
            )

        // Échauffement : laisse le JIT compiler le moteur, sinon les premières mesures décrivent le démarrage à froid.
        repeat(WARMUP_ROUNDS) { corpora.forEach { corpus -> corpus.texts.forEach { correct(it) } } }

        var measured = 0L
        for (corpus in corpora) {
            val histogram = LatencyHistogram()
            repeat(ROUNDS) {
                for (text in corpus.texts) {
                    val started = System.nanoTime()
                    correct(text)
                    histogram.record(System.nanoTime() - started)
                }
            }
            measured += histogram.count
            val line = "Latence ${corpus.label} : n=${histogram.count} p50<=${histogram.percentileMicros(0.50)}us " +
                "p95<=${histogram.percentileMicros(0.95)}us p99<=${histogram.percentileMicros(0.99)}us " +
                "max=${histogram.maxMicros}us lentes(>=16ms)=${histogram.slowCount}"
            Log.i(TAG, line)
            println(line)
        }
        assertTrue("rien n'a été mesuré", measured > 0L)
    }

    private companion object {
        const val TAG = "AutocorrectionLatency"
        const val WARMUP_ROUNDS = 5
        const val ROUNDS = 30
    }
}
