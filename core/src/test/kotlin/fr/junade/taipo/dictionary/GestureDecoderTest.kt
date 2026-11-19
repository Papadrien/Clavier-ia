package fr.junade.taipo.dictionary

import java.io.File
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Saisie par glissement (19/11/2026) : décodage d'un tracé en mots, sur une disposition AZERTY simulée. */
class GestureDecoderTest {

    @Test
    fun `un trace propre donne le mot`() {
        val decoder = GestureDecoder(SMALL.keys.toTypedArray(), SMALL.values.toLongArray(), AZERTY.keys)
        for (word in listOf("bonjour", "merci", "salut", "maison", "avec", "tout")) {
            val result = decoder.decode(trace(word, Random(1), jitter = 0f), AZERTY)
            assertEquals(word, result.firstOrNull()?.word, "$word -> $result")
        }
    }

    @Test
    fun `a forme egale le mot le plus frequent passe devant`() {
        // « tour » et « tout » : même début, dernières lettres voisines (R et T) ; le tracé finit entre les deux.
        val decoder = GestureDecoder(arrayOf("tour", "tout"), longArrayOf(1_000L, 100_000L), AZERTY.keys)
        val path = trace("tou", Random(2), jitter = 0f) + GestureDecoder.Point(4.5f, ROW)
        assertEquals("tout", decoder.decode(path, AZERTY).first().word)
    }

    @Test
    fun `les lettres accentuees suivent leur lettre de base et les doubles ne comptent qu une fois`() {
        val decoder = GestureDecoder(arrayOf("été", "elle"), longArrayOf(10L, 10L), AZERTY.keys)
        assertEquals("été", decoder.decode(trace("ete", Random(3), 0f), AZERTY).first().word)
        assertEquals("elle", decoder.decode(trace("ele", Random(3), 0f), AZERTY).first().word)
        assertEquals("ete", GestureDecoder.spell("Été", AZERTY.keys))
        assertEquals("å", GestureDecoder.spell("Å", setOf('å')))
    }

    @Test
    fun `un mot dont une lettre est loin du trace est ecarte`() {
        val decoder = GestureDecoder(arrayOf("bonjour"), longArrayOf(1_000_000L), AZERTY.keys)
        // Tracé de « bout » : il ne passe ni par N, ni par J.
        assertTrue(decoder.decode(trace("bout", Random(4), 0f), AZERTY).isEmpty())
    }

    @Test
    fun `un appui sans deplacement ne donne rien`() {
        val decoder = GestureDecoder(SMALL.keys.toTypedArray(), SMALL.values.toLongArray(), AZERTY.keys)
        val p = AZERTY.getValue('a')
        assertTrue(decoder.decode(listOf(p, p), AZERTY).isEmpty())
    }

    @Test
    fun `les mots personnels sont candidats`() {
        val decoder = GestureDecoder(arrayOf("bonjour"), longArrayOf(1_000_000L), AZERTY.keys)
        assertEquals("Taipo", decoder.decode(trace("taipo", Random(5), 0f), AZERTY, extraWords = listOf("Taipo")).first().word)
    }

    /**
     * Banc sur le vrai dictionnaire français (mots proposables, comme le clavier) : 400 mots courants de 3 lettres ou plus,
     * tracés avec un doigt imprécis (écart de 0,22 touche autour de chaque lettre). Le mot doit être premier dans 90 % des cas
     * au moins, et parmi les trois premiers (la bande de suggestions) dans 97 % des cas. Mesure du 19/11/2026 : 94,0 % et
     * 99,8 % ; les manqués restants sont des homographes sans accent (été / être, sur / sûr) ou des voisins (vos / vous).
     */
    @Test
    fun `banc sur le dictionnaire francais`() {
        val root = findRoot() ?: return
        val frequencies = File(root, "dictionaries/fr.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, FR_CANDIDATE_MIN)
        }
        val dictionary = Dictionary.withFrequencies(frequencies, InflectionRules.NONE, FR_CANDIDATE_MIN)
        val decoder = GestureDecoder.forDictionary(dictionary, AZERTY.keys)
        val random = Random(42)
        val sample = frequencies.entries.sortedByDescending { it.value }.map { it.key }
            .filter { it.length >= 3 && it.all { c -> c.isLetter() } }
            .take(BENCH_WORDS)
        val started = System.nanoTime()
        var first = 0
        var topThree = 0
        val misses = ArrayList<String>()
        for (word in sample) {
            val result = decoder.decode(trace(GestureDecoder.spell(word, AZERTY.keys), random, BENCH_JITTER), AZERTY).map { it.word }
            if (result.firstOrNull() == word) first++ else misses += "$word->${result.take(3)}"
            if (word in result.take(3)) topThree++
        }
        val averageMs = (System.nanoTime() - started) / 1_000_000.0 / sample.size
        val firstRate = first.toDouble() / sample.size
        val topRate = topThree.toDouble() / sample.size
        println(
            "Glissement FR : premier ${"%.1f".format(firstRate * 100)} %, trois premiers ${"%.1f".format(topRate * 100)} %, " +
                "${"%.2f".format(averageMs)} ms par tracé",
        )
        println("Manqués (extrait) : ${misses.take(40)}")
        assertTrue(firstRate >= 0.90, "premier : $firstRate")
        assertTrue(topRate >= 0.97, "trois premiers : $topRate")
    }

    private companion object {
        /** Hauteur d'une rangée en largeurs de touche (touche de téléphone : plus haute que large). */
        const val ROW_HEIGHT = 1.4f
        const val ROW = 0.5f * ROW_HEIGHT
        const val BENCH_WORDS = 400
        const val BENCH_JITTER = 0.22f
        const val FR_CANDIDATE_MIN = 2_000L

        /** Centres des touches d'un AZERTY de téléphone, en largeurs de touche. */
        val AZERTY: Map<Char, GestureDecoder.Point> = buildMap {
            "azertyuiop".forEachIndexed { i, c -> put(c, GestureDecoder.Point(0.5f + i, ROW)) }
            "qsdfghjklm".forEachIndexed { i, c -> put(c, GestureDecoder.Point(0.5f + i, ROW + ROW_HEIGHT)) }
            "wxcvbn'".forEachIndexed { i, c -> put(c, GestureDecoder.Point(2f + i, ROW + 2 * ROW_HEIGHT)) }
        }

        val SMALL = linkedMapOf(
            "bonjour" to 50_000L, "bonsoir" to 8_000L, "merci" to 60_000L, "salut" to 20_000L, "sale" to 3_000L,
            "maison" to 30_000L, "mais" to 400_000L, "avec" to 500_000L, "tout" to 450_000L, "tour" to 20_000L,
        )

        /**
         * Tracé simulé de [spelled] : de centre en centre (chaque lettre décalée au hasard de [jitter] touche), avec un point
         * tous les dixièmes de touche, comme les événements tactiles d'un vrai doigt.
         */
        fun trace(spelled: String, random: Random, jitter: Float): List<GestureDecoder.Point> {
            val centers = GestureDecoder.idealPath(spelled, AZERTY).orEmpty().map {
                GestureDecoder.Point(it.x + (random.nextGaussian() * jitter).toFloat(), it.y + (random.nextGaussian() * jitter).toFloat())
            }
            val out = ArrayList<GestureDecoder.Point>()
            for (i in 1 until centers.size) {
                val a = centers[i - 1]
                val b = centers[i]
                val steps = maxOf(1, (GestureDecoder.pathLength(listOf(a, b)) * 10).toInt())
                for (s in 0 until steps) {
                    val t = s.toFloat() / steps
                    out += GestureDecoder.Point(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                }
            }
            centers.lastOrNull()?.let { out += it }
            return out
        }

        fun findRoot(): File? {
            var dir: File? = File("").absoluteFile
            while (dir != null) {
                if (File(dir, "dictionaries/fr.txt").isFile) return dir
                dir = dir.parentFile
            }
            return null
        }
    }
}
