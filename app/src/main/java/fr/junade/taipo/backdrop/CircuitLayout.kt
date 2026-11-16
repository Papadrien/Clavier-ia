package fr.junade.taipo.backdrop

import java.util.Random
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Plan du fond « Processeur » (maquette validée le 16/11/2026) : un processeur au centre, des bus de pistes qui en partent
 * vers les bords, quelques condensateurs et résistances, et des pistes isolées terminées par des vias. Logique pure (JVM),
 * testée dans `CircuitLayoutTest` ; le dessin est dans [ProcessorPainter].
 *
 * Les pistes sont routées sur une grille de [ROWS] rangées (cellule carrée, taille déduite de la hauteur), à 0°, 45° ou
 * 90°, et ne passent jamais deux fois par la même cellule : elles ne se croisent pas.
 */
internal class CircuitLayout(
    val cell: Float,
    val cpu: Box,
    val parts: List<Part>,
    val pads: List<Pin>,
    val traces: List<Trace>,
) {
    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    enum class Kind { CAPACITOR, RESISTOR }

    class Part(val kind: Kind, val box: Box)

    /** Broche en ([x], [y]), sur le bord d'un composant, tournée vers [direction] (indice de [DIRECTIONS] : 0, 2, 4 ou 6). */
    class Pin(val x: Float, val y: Float, val direction: Int)

    /**
     * Piste : ligne brisée [xs] / [ys]. Le premier point est une broche ([fromCpu] : une broche du processeur, c'est alors un
     * bus) ou un via ([startsAtVia]) ; le dernier point est toujours un via.
     */
    class Trace(val xs: FloatArray, val ys: FloatArray, val fromCpu: Boolean, val startsAtVia: Boolean) {
        /** Longueur cumulée jusqu'à chaque point. */
        val lengths = FloatArray(xs.size).also { l ->
            for (i in 1 until xs.size) l[i] = l[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        val length: Float get() = lengths.last()

        /** Position le long de la piste (depuis son premier point) du point le plus proche de ([x], [y]), et sa distance. */
        fun nearest(x: Float, y: Float): Nearest {
            var best = Nearest(0f, Float.MAX_VALUE)
            for (i in 0 until xs.size - 1) {
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val segment = lengths[i + 1] - lengths[i]
                val t = if (segment <= 0f) 0f else (((x - xs[i]) * dx + (y - ys[i]) * dy) / (segment * segment)).coerceIn(0f, 1f)
                val d = hypot(xs[i] + dx * t - x, ys[i] + dy * t - y)
                if (d < best.distance) best = Nearest(lengths[i] + segment * t, d)
            }
            return best
        }
    }

    data class Nearest(val position: Float, val distance: Float)

    companion object {
        /** Rangées de la grille : la cellule mesure la hauteur divisée par ce nombre. */
        const val ROWS = 26
        const val CPU_COLS = 8
        const val CPU_ROWS = 6

        /** Huit directions, dans le sens horaire à partir de la droite (y vers le bas). */
        val DIRECTIONS = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(1, 1),
            intArrayOf(0, 1),
            intArrayOf(-1, 1),
            intArrayOf(-1, 0),
            intArrayOf(-1, -1),
            intArrayOf(0, -1),
            intArrayOf(1, -1),
        )

        fun generate(width: Float, height: Float, random: Random): CircuitLayout = Builder(width, height, random).build()

        private const val PORTRAIT_COLS = 36f
        private const val CAPACITORS = 4
        private const val RESISTORS = 2
        private const val LOOSE_TRACES = 4
        private const val PART_COLS = 2
        private const val ATTEMPTS_PER_ITEM = 80
        private const val MIN_LENGTH = 6
        private const val MAX_LENGTH = 34
        private const val MIN_CELLS = 3
        private const val TURN_CHANCE = 0.1f
        private const val STOP_CHANCE = 0.35f
        private val TURNS = intArrayOf(0, 1, 7)
    }

    private class Builder(width: Float, height: Float, private val random: Random) {
        val cell = height / ROWS
        val cols = (width / cell).toInt().coerceAtLeast(CPU_COLS + 2)
        val rows = (height / cell).toInt().coerceAtLeast(CPU_ROWS + 2)
        val originX = (width - cols * cell) / 2f
        val originY = (height - rows * cell) / 2f
        val occupied = BooleanArray(cols * rows)
        val parts = ArrayList<Part>()
        val pads = ArrayList<Pin>()
        val traces = ArrayList<Trace>()

        /** Broche à router : cellule de départ ([i], [j]), direction, et point sur le bord du composant. */
        inner class Start(val i: Int, val j: Int, val direction: Int, val pin: Pin)

        fun build(): CircuitLayout {
            val cpuI = (cols - CPU_COLS) / 2
            val cpuJ = (rows - CPU_ROWS) / 2 + 1
            reserve(cpuI, cpuJ, CPU_COLS, CPU_ROWS)
            val cpu = box(cpuI, cpuJ, CPU_COLS, CPU_ROWS)
            val cpuStarts = sides(cpuI, cpuJ, CPU_COLS, CPU_ROWS, cpu)
            // Assez de condensateurs et de résistances pour une largeur de clavier en portrait, davantage s'il est plus large.
            val scale = (cols / PORTRAIT_COLS).coerceAtLeast(1f)
            val partStarts = ArrayList<Start>()
            repeat((CAPACITORS * scale).roundToInt()) { place(Kind.CAPACITOR)?.let(partStarts::addAll) }
            repeat((RESISTORS * scale).roundToInt()) { place(Kind.RESISTOR)?.let(partStarts::addAll) }
            cpuStarts.forEach { route(it.i, it.j, it.direction, it.pin, fromCpu = true) }
            partStarts.forEach { route(it.i, it.j, it.direction, it.pin, fromCpu = false) }
            val loose = (LOOSE_TRACES * scale).roundToInt()
            var made = 0
            var attempts = 0
            while (made < loose && attempts < loose * ATTEMPTS_PER_ITEM) {
                attempts++
                val i = random.nextInt(cols)
                val j = random.nextInt(rows)
                if (isFree(i, j) && route(i, j, random.nextInt(4) * 2, null, fromCpu = false)) made++
            }
            return CircuitLayout(cell, cpu, parts, pads, traces)
        }

        private fun place(kind: Kind): List<Start>? {
            repeat(ATTEMPTS_PER_ITEM) {
                val i = 1 + random.nextInt((cols - PART_COLS - 2).coerceAtLeast(1))
                val j = 1 + random.nextInt((rows - 3).coerceAtLeast(1))
                if (fits(i, j, PART_COLS, 1)) {
                    reserve(i, j, PART_COLS, 1)
                    val b = box(i, j, PART_COLS, 1)
                    parts += Part(kind, b)
                    val y = centerY(j)
                    return listOf(start(i - 1, j, 4, b.left, y), start(i + PART_COLS, j, 0, b.right, y))
                }
            }
            return null
        }

        /** Broches sur les quatre côtés d'un composant de [w] × [h] cellules. */
        private fun sides(i: Int, j: Int, w: Int, h: Int, b: Box): List<Start> {
            val out = ArrayList<Start>()
            for (k in 0 until h) {
                out += start(i - 1, j + k, 4, b.left, centerY(j + k))
                out += start(i + w, j + k, 0, b.right, centerY(j + k))
            }
            for (k in 0 until w) {
                out += start(i + k, j - 1, 6, centerX(i + k), b.top)
                out += start(i + k, j + h, 2, centerX(i + k), b.bottom)
            }
            return out
        }

        private fun start(i: Int, j: Int, direction: Int, x: Float, y: Float): Start {
            val pin = Pin(x, y, direction)
            pads += pin
            return Start(i, j, direction, pin)
        }

        /** Route une piste depuis la cellule ([i0], [j0]) ; false si elle est trop courte pour être gardée. */
        private fun route(i0: Int, j0: Int, direction0: Int, pin: Pin?, fromCpu: Boolean): Boolean {
            if (pin == null && !isFree(i0, j0)) return false
            // La cellule de départ d'une broche est déjà réservée par son composant ; celle d'une piste isolée est prise ici.
            if (pin == null) occupied[j0 * cols + i0] = true
            val cells = walk(i0, j0, direction0)
            if (cells.size < MIN_CELLS) {
                // Trop courte : on rend les cellules prises.
                cells.drop(if (pin == null) 0 else 1).forEach { occupied[it[1] * cols + it[0]] = false }
                return false
            }
            traces += toTrace(cells, pin, fromCpu)
            return true
        }

        /** Avance d'une cellule à la fois, tout droit de préférence, en tournant parfois de 45° ; s'arrête une fois bloquée. */
        private fun walk(i0: Int, j0: Int, direction0: Int): List<IntArray> {
            val cells = arrayListOf(intArrayOf(i0, j0))
            var current = intArrayOf(i0, j0, direction0)
            val length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH)
            for (k in 0 until length) {
                if (k > 1 && random.nextFloat() < TURN_CHANCE) current[2] = (current[2] + if (random.nextBoolean()) 1 else 7) % 8
                current = step(current) ?: break
                cells += intArrayOf(current[0], current[1])
                occupied[current[1] * cols + current[0]] = true
            }
            return cells
        }

        /** Cellule suivante depuis (i, j, direction) : tout droit, sinon à 45° d'un côté ou de l'autre ; null si la piste s'arrête. */
        private fun step(from: IntArray): IntArray? {
            for (turn in TURNS) {
                val d = (from[2] + turn) % 8
                val i = from[0] + DIRECTIONS[d][0]
                val j = from[1] + DIRECTIONS[d][1]
                if (isFree(i, j)) return intArrayOf(i, j, d)
                // Tout droit bloqué : la piste s'arrête parfois là plutôt que de contourner.
                if (turn == 0 && random.nextFloat() < STOP_CHANCE) return null
            }
            return null
        }

        /** Ligne brisée : la broche (s'il y en a une), puis le centre des cellules où la piste change de direction. */
        private fun toTrace(cells: List<IntArray>, pin: Pin?, fromCpu: Boolean): Trace {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            if (pin != null) {
                xs += pin.x
                ys += pin.y
            }
            for (k in cells.indices) {
                if (k == 0 || k == cells.size - 1 || !sameDirection(cells[k - 1], cells[k], cells[k + 1])) {
                    xs += centerX(cells[k][0])
                    ys += centerY(cells[k][1])
                }
            }
            return Trace(xs.toFloatArray(), ys.toFloatArray(), fromCpu, startsAtVia = pin == null)
        }

        private fun sameDirection(a: IntArray, b: IntArray, c: IntArray) = b[0] - a[0] == c[0] - b[0] && b[1] - a[1] == c[1] - b[1]

        private fun isInside(i: Int, j: Int) = i in 0 until cols && j in 0 until rows

        private fun isFree(i: Int, j: Int) = isInside(i, j) && !occupied[j * cols + i]

        /** Le composant et une cellule de marge tout autour sont libres. */
        private fun fits(i: Int, j: Int, w: Int, h: Int): Boolean {
            for (y in j - 1..j + h) for (x in i - 1..i + w) if (!isFree(x, y)) return false
            return true
        }

        private fun reserve(i: Int, j: Int, w: Int, h: Int) {
            for (y in j - 1..j + h) for (x in i - 1..i + w) if (isInside(x, y)) occupied[y * cols + x] = true
        }

        private fun box(i: Int, j: Int, w: Int, h: Int) =
            Box(originX + i * cell, originY + j * cell, originX + (i + w) * cell, originY + (j + h) * cell)

        private fun centerX(i: Int) = originX + (i + 0.5f) * cell

        private fun centerY(j: Int) = originY + (j + 0.5f) * cell
    }
}
