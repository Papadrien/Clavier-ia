package fr.junade.taipo.backdrop

import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Plan du fond Processeur (16/11/2026) : processeur centré, bus routés à 45° près, sans croisement. */
class CircuitLayoutTest {

    private val sizes = listOf(1080f to 786f, 400f to 262f, 2400f to 700f)

    private fun plan(w: Float, h: Float, seed: Long = 7L) = CircuitLayout.generate(w, h, Random(seed))

    @Test
    fun `meme graine, meme circuit`() {
        val a = plan(1080f, 786f, 42L)
        val b = plan(1080f, 786f, 42L)
        assertEquals(a.traces.size, b.traces.size)
        a.traces.zip(b.traces).forEach { (x, y) -> assertTrue(x.xs.contentEquals(y.xs) && x.ys.contentEquals(y.ys)) }
    }

    @Test
    fun `le processeur est au centre et des bus en partent`() {
        sizes.forEach { (w, h) ->
            val p = plan(w, h)
            assertTrue(abs(p.cpu.centerX - w / 2f) <= p.cell, "centré en largeur pour $w×$h")
            assertTrue(abs(p.cpu.centerY - h / 2f) <= 2 * p.cell, "centré en hauteur pour $w×$h")
            val buses = p.traces.filter { it.fromCpu }
            assertTrue(buses.size >= 8, "au moins 8 bus pour $w×$h (${buses.size})")
            buses.forEach { t ->
                val x = t.xs[0]
                val y = t.ys[0]
                val onEdge = (abs(x - p.cpu.left) < 0.01f || abs(x - p.cpu.right) < 0.01f) &&
                    y in p.cpu.top..p.cpu.bottom ||
                    (abs(y - p.cpu.top) < 0.01f || abs(y - p.cpu.bottom) < 0.01f) &&
                    x in p.cpu.left..p.cpu.right
                assertTrue(onEdge, "un bus part d'une broche du processeur")
            }
        }
    }

    @Test
    fun `les pistes vont a 0, 45 ou 90 degres et restent dans le fond`() {
        sizes.forEach { (w, h) ->
            val p = plan(w, h)
            p.traces.forEach { t ->
                for (i in 0 until t.xs.size - 1) {
                    val dx = abs(t.xs[i + 1] - t.xs[i])
                    val dy = abs(t.ys[i + 1] - t.ys[i])
                    assertTrue(dx < 0.01f || dy < 0.01f || abs(dx - dy) < 0.01f, "segment à 45° près")
                }
                t.xs.forEach { assertTrue(it in 0f..w) }
                t.ys.forEach { assertTrue(it in 0f..h) }
            }
        }
    }

    @Test
    fun `deux pistes ne passent jamais par la meme cellule`() {
        val p = plan(1080f, 786f)
        val seen = HashSet<Pair<Int, Int>>()
        p.traces.forEach { t -> cellsOf(t, p.cell).forEach { assertTrue(seen.add(it), "cellule $it utilisée deux fois") } }
    }

    /**
     * Centres des cellules traversées par une piste, arrondis au dixième (le premier point d'un bus est sur le bord du
     * processeur et non au centre d'une cellule : on part du suivant).
     */
    private fun cellsOf(t: CircuitLayout.Trace, cell: Float): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val from = if (t.startsAtVia) 0 else 1
        for (i in from until t.xs.size - 1) {
            val steps = (maxOf(abs(t.xs[i + 1] - t.xs[i]), abs(t.ys[i + 1] - t.ys[i])) / cell).roundToInt()
            val last = if (i == t.xs.size - 2) steps else steps - 1
            for (k in 0..last) {
                val x = t.xs[i] + (t.xs[i + 1] - t.xs[i]) * k / steps
                val y = t.ys[i] + (t.ys[i + 1] - t.ys[i]) * k / steps
                out += (x * 10).roundToInt() to (y * 10).roundToInt()
            }
        }
        return out
    }

    @Test
    fun `point le plus proche sur une piste`() {
        val t = CircuitLayout.Trace(floatArrayOf(0f, 10f, 20f), floatArrayOf(0f, 0f, 10f), fromCpu = true, startsAtVia = false)
        val n = t.nearest(5f, 3f)
        assertEquals(5f, n.position, 0.001f)
        assertEquals(3f, n.distance, 0.001f)
        assertEquals(10f + 14.142f, t.length, 0.01f)
    }
}
