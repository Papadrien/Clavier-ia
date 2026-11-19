package fr.junade.taipo.dictionary

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * Saisie par glissement (19/11/2026), sur le principe de Gboard et SwiftKey : le doigt part de la première lettre et passe sur
 * les suivantes sans se lever ; au relâchement, le tracé est comparé au trajet « idéal » de chaque mot candidat (de centre de
 * touche en centre de touche), et les mots les plus proches, pondérés par leur fréquence, sont proposés.
 *
 * Le décodage suit les grandes lignes de SHARK² (Kristensson et Zhai) :
 * 1. le tracé est rééchantillonné en [SAMPLES] points également espacés ;
 * 2. seuls les mots dont la première lettre est proche du début du tracé et la dernière proche de sa fin sont examinés (index
 *    par paire de lettres, construit une fois) ;
 * 3. chaque lettre du mot doit être frôlée par le tracé, dans l'ordre ([PASS_RADIUS]) : un mot dont une lettre est loin du
 *    doigt est écarté ;
 * 4. le coût d'un mot est l'écart moyen entre les deux tracés rééchantillonnés (canal de position), auquel s'ajoute une pénalité
 *    de rareté (logarithme de la fréquence) : à forme égale, le mot courant l'emporte.
 *
 * Les coordonnées sont en largeurs de touche (la vue divise ses pixels par la largeur d'une touche de lettre) : les seuils
 * valent pour toutes les tailles d'écran. Une lettre accentuée sans touche propre suit la touche de sa lettre de base (« été »
 * passe par E, T, E) ; une lettre qui a sa touche (å, ä, ö, ñ sur les claviers de ces langues, [keyChars]) la garde ; un
 * caractère sans touche (trait d'union) est ignoré ; deux lettres identiques de suite ne font qu'un passage.
 *
 * Logique pure, sans Android, testée en JVM (`GestureDecoderTest`).
 */
class GestureDecoder(private val words: Array<String>, private val frequencies: LongArray, private val keyChars: Set<Char>) {

    /** Point du tracé ou centre de touche, en largeurs de touche. */
    data class Point(val x: Float, val y: Float)

    /** Mot proposé et son coût (plus petit = meilleur). */
    data class Candidate(val word: String, val cost: Float)

    private val maxFrequency: Long = max(1L, frequencies.maxOrNull() ?: 1L)

    /** Formes sans accents ni majuscules, calculées une fois (même ordre que [words]). */
    private val folded: Array<String> = Array(words.size) { spell(words[it], keyChars) }

    /** Index des mots par (première lettre, dernière lettre) de leur forme repliée. */
    private val byEnds: Map<Int, IntArray> = run {
        val groups = HashMap<Int, MutableList<Int>>()
        for (i in folded.indices) {
            val f = folded[i]
            if (f.length < 2) continue
            groups.getOrPut(endsKey(f.first(), f.last())) { ArrayList() } += i
        }
        groups.mapValues { (_, list) -> list.toIntArray() }
    }

    /**
     * Mots les plus proches du tracé [path] sur le clavier dont les centres de touches sont [keys] (lettre minuscule -> centre),
     * au plus [limit], du meilleur au moins bon. [extraWords] : mots personnels, toujours candidats, comptés comme les plus
     * fréquents. Vide si le tracé est trop court ou qu'aucun mot ne passe par les lettres frôlées.
     */
    fun decode(path: List<Point>, keys: Map<Char, Point>, extraWords: Collection<String> = emptyList(), limit: Int = 4): List<Candidate> {
        if (path.size < 2 || keys.isEmpty()) return emptyList()
        val gesture = resample(path, SAMPLES)
        val length = pathLength(path)
        if (length < MIN_GESTURE_LENGTH) return emptyList()
        val dense = resample(path, (length / DENSE_STEP).toInt().coerceIn(SAMPLES, MAX_DENSE_SAMPLES))
        val starts = nearKeys(gesture.first(), keys)
        val ends = nearKeys(gesture.last(), keys)
        val best = ArrayList<Candidate>()
        val seen = HashSet<String>()
        fun consider(word: String, foldedWord: String, frequency: Long) {
            if (!seen.add(word)) return
            val cost = costOf(foldedWord, frequency, gesture, dense, length, keys) ?: return
            best += Candidate(word, cost)
        }
        for (s in starts) {
            for (e in ends) {
                val indices = byEnds[endsKey(s, e)] ?: continue
                for (i in indices) consider(words[i], folded[i], frequencies[i])
            }
        }
        for (word in extraWords) {
            val f = spell(word, keyChars)
            if (f.length >= 2 && f.first() in starts && f.last() in ends) consider(word, f, maxFrequency)
        }
        best.sortBy { it.cost }
        return best.take(limit)
    }

    /** Coût de [foldedWord] pour le tracé rééchantillonné [gesture], ou null si le mot ne suit pas le tracé. */
    private fun costOf(
        foldedWord: String,
        frequency: Long,
        gesture: List<Point>,
        dense: List<Point>,
        gestureLength: Float,
        keys: Map<Char, Point>,
    ): Float? {
        val ideal = idealPath(foldedWord, keys) ?: return null
        if (ideal.size < 2) return null
        val idealLength = pathLength(ideal)
        // Longueurs trop différentes : le mot ne peut pas correspondre (test rapide, avant le rééchantillonnage).
        if (idealLength > gestureLength * MAX_LENGTH_RATIO + LENGTH_SLACK || idealLength < gestureLength * MIN_LENGTH_RATIO - LENGTH_SLACK) {
            return null
        }
        if (!passesThrough(ideal, dense)) return null
        val sampled = resample(ideal, SAMPLES)
        var sum = 0f
        for (i in 0 until SAMPLES) sum += distance(sampled[i], gesture[i])
        val mean = sum / SAMPLES
        val shape = (mean / SIGMA) * (mean / SIGMA) / 2f
        val rarity = -ln(max(frequency, 1L).toDouble() / maxFrequency).toFloat()
        return shape + FREQUENCY_WEIGHT * rarity
    }

    /**
     * Chaque lettre du trajet idéal doit être frôlée par le tracé (à moins de [PASS_RADIUS]), et dans l'ordre : pour chaque
     * lettre, à partir du passage de la précédente, on retient le premier endroit où le doigt s'en approche (minimum local de la
     * distance, sous le rayon). Une lettre visitée deux fois, comme les deux O de « bonjour », garde ainsi chacun de ses
     * passages. [dense] : le tracé échantillonné finement ([DENSE_STEP]), pour ne manquer aucun passage d'un long mot.
     */
    private fun passesThrough(ideal: List<Point>, dense: List<Point>): Boolean {
        var from = 0
        for (key in ideal) {
            var found = -1
            var i = from
            while (i < dense.size) {
                val d = distance(key, dense[i])
                if (d <= PASS_RADIUS && (i == dense.size - 1 || distance(key, dense[i + 1]) >= d)) {
                    found = i
                    break
                }
                i++
            }
            if (found < 0) return false
            from = found
        }
        return true
    }

    companion object {
        /** Nombre de points des tracés comparés. */
        const val SAMPLES = 48

        /** Longueur minimale (largeurs de touche) d'un tracé décodé : en dessous, c'est un simple appui. */
        const val MIN_GESTURE_LENGTH = 0.8f

        /** Distance maximale (largeurs de touche) entre une lettre du mot et le tracé. */
        const val PASS_RADIUS = 1.05f

        /** Rayon (largeurs de touche) des touches candidates pour la première et la dernière lettre. */
        const val END_RADIUS = 1.25f

        /** Au plus autant de touches candidates pour la première et pour la dernière lettre (les plus proches). */
        const val END_KEYS = 4

        /** Écart type du canal de position (largeurs de touche). */
        const val SIGMA = 0.45f

        /** Poids de la rareté d'un mot (logarithme du rapport à la fréquence la plus haute). */
        const val FREQUENCY_WEIGHT = 0.12f

        /** Pas (largeurs de touche) du tracé fin qui sert à vérifier le passage par chaque lettre. */
        private const val DENSE_STEP = 0.2f
        private const val MAX_DENSE_SAMPLES = 400
        private const val MAX_LENGTH_RATIO = 1.7f
        private const val MIN_LENGTH_RATIO = 0.45f
        private const val LENGTH_SLACK = 1f

        /** Décodeur des mots proposables d'un dictionnaire (ceux que l'autocorrection peut proposer), pour les touches [keyChars]. */
        fun forDictionary(dictionary: Dictionary, keyChars: Set<Char>): GestureDecoder =
            GestureDecoder(dictionary.candidateWords, dictionary.candidateFrequencies, keyChars)

        /**
         * Lettres de [word] telles que le doigt les parcourt : minuscules ; une lettre absente des touches [keyChars] est repliée
         * sur sa lettre de base (é -> e, ç -> c, œ -> oe).
         */
        fun spell(word: String, keyChars: Set<Char>): String {
            val lower = word.lowercase()
            val out = StringBuilder(lower.length)
            for (c in lower) {
                if (c in keyChars || c.code < ASCII_LIMIT) out.append(c) else out.append(WordForms.foldAccents(c.toString()))
            }
            return out.toString()
        }

        private const val ASCII_LIMIT = 128

        /** Trajet idéal de [foldedWord] : centres des touches de ses lettres, sans répéter une lettre doublée ; null si vide. */
        fun idealPath(foldedWord: String, keys: Map<Char, Point>): List<Point>? {
            val points = ArrayList<Point>(foldedWord.length)
            var previous: Char? = null
            for (c in foldedWord) {
                val p = keys[c]
                if (p != null && c != previous) {
                    points += p
                    previous = c
                }
            }
            return points.takeIf { it.isNotEmpty() }
        }

        /** [path] rééchantillonné en [n] points également espacés le long du tracé. */
        fun resample(path: List<Point>, n: Int): List<Point> {
            val total = pathLength(path)
            if (path.size == 1 || total <= 0f) return List(n) { path.first() }
            val step = total / (n - 1)
            val out = ArrayList<Point>(n)
            out += path.first()
            var carried = 0f
            var i = 1
            var prev = path.first()
            while (i < path.size && out.size < n) {
                val next = path[i]
                val segment = distance(prev, next)
                if (carried + segment >= step && segment > 0f) {
                    val t = (step - carried) / segment
                    val q = Point(prev.x + t * (next.x - prev.x), prev.y + t * (next.y - prev.y))
                    out += q
                    prev = q
                    carried = 0f
                } else {
                    carried += segment
                    prev = next
                    i++
                }
            }
            while (out.size < n) out += path.last()
            return out
        }

        fun pathLength(path: List<Point>): Float {
            var sum = 0f
            for (i in 1 until path.size) sum += distance(path[i - 1], path[i])
            return sum
        }

        private fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

        /** Lettres dont la touche est à moins de [END_RADIUS] de [p], au plus [END_KEYS], la plus proche toujours comprise. */
        private fun nearKeys(p: Point, keys: Map<Char, Point>): Set<Char> {
            val sorted = keys.entries.sortedBy { distance(p, it.value) }
            val near = sorted.filter { distance(p, it.value) <= END_RADIUS }.take(END_KEYS).map { it.key }
            return (near.ifEmpty { listOf(sorted.first().key) }).toSet()
        }

        private fun endsKey(first: Char, last: Char): Int = (first.code shl 16) or last.code
    }
}
