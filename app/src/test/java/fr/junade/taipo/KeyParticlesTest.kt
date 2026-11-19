package fr.junade.taipo

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Particules de la bulle des touches (styles Étincelles, Pixels, Bulle cœur, Éclosion, 19/11/2026). */
class KeyParticlesTest {

    private val key = KeyParticles.Box(100f, 300f, 30f, 40f)
    private val unit = 30f

    @Test
    fun `les etincelles partent vers le haut puis retombent, sans cercle`() {
        val p = KeyParticles(Random(3))
        p.sparks(0L, 115f, 250f, unit, 1, 2, key)
        val sparks = p.particles.filter { it.kind == KeyParticles.Kind.SPARK }
        assertEquals(KeyParticles.SPARK_COUNT, sparks.size)
        // Une lueur sur la touche, et rien d'autre : pas d'onde de choc (retirée à la demande du 19/11/2026).
        assertEquals(listOf(KeyParticles.Kind.FLASH), p.particles.map { it.kind }.filter { it != KeyParticles.Kind.SPARK })
        val s = sparks.minBy { it.vy }
        assertTrue(s.vy < 0f, "au moins une étincelle part vers le haut")
        assertTrue(p.yAt(s, 50L) < s.y, "monte d'abord")
        assertTrue(p.yAt(s, 900L) > p.yAt(s, 450L), "puis retombe")
    }

    @Test
    fun `la bulle pixels se brise en une grille de carres`() {
        val p = KeyParticles(Random(1))
        p.shatter(0L, KeyParticles.Box(90f, 200f, 48f, 60f), unit, 1, 2)
        assertEquals(KeyParticles.PIXEL_COLUMNS * KeyParticles.PIXEL_ROWS, p.particles.size)
        assertTrue(p.particles.all { it.kind == KeyParticles.Kind.SQUARE })
    }

    @Test
    fun `jamais plus de 40 particules, les plus anciennes partent d abord`() {
        val p = KeyParticles(Random(2))
        repeat(5) { p.shatter(it.toLong(), KeyParticles.Box(0f, 0f, 48f, 60f), unit, 1, 2) }
        assertEquals(KeyParticles.MAX_PARTICLES, p.particles.size)
        assertEquals(4L, p.particles.last().startAt)
        assertTrue(p.particles.first().startAt >= 3L)
    }

    @Test
    fun `les particules s effacent puis sont oubliees`() {
        val p = KeyParticles(Random(4))
        p.heartTrail(0L, 115f, 260f, 48f, unit, 1)
        val h = p.particles.first()
        assertTrue(p.progress(h, 0L) < 0f, "les cœurs partent avec un léger décalage")
        assertEquals(1f, p.alphaAt(h, h.startAt), 1e-4f)
        assertTrue(p.alphaAt(h, h.startAt + h.lifeMs - 1) < 0.01f)
        assertTrue(p.yAt(h, h.startAt + 300) < h.y, "les cœurs montent")
        assertTrue(p.isRunning(100L))
        p.prune(10_000L)
        assertTrue(p.particles.isEmpty())
        assertFalse(p.isRunning(10_000L))
    }

    @Test
    fun `eclosion, cinq petales tombent et le coeur reste sur la touche`() {
        val p = KeyParticles(Random(5))
        val flower = KeyParticles.Flower(115f, 250f, 13f, 23f, 13f)
        p.petalFall(0L, flower, unit, IntArray(5) { it }, key, 9)
        val petals = p.particles.filter { it.kind == KeyParticles.Kind.PETAL }
        assertEquals(5, petals.size)
        val dot = p.particles.single { it.kind == KeyParticles.Kind.DOT }
        assertEquals(key.centerX, dot.x)
        assertEquals(key.centerY, dot.y)
        assertTrue(petals.all { p.yAt(it, 900L) > p.yAt(it, 0L) }, "les pétales finissent par tomber")
    }

    @Test
    fun `pixels clignotent a l appui`() {
        val p = KeyParticles(Random(6))
        p.blink(0L, key, unit, 1)
        assertTrue(p.particles.all { it.kind == KeyParticles.Kind.BLINK })
        assertTrue(p.particles.all { it.x in key.left..(key.left + key.width) && it.y in key.top..(key.top + key.height) })
        val b = p.particles.first()
        assertEquals(1f, p.alphaAt(b, b.startAt))
        assertTrue(p.alphaAt(b, b.startAt + b.lifeMs / 4 + 1) < 0.5f)
    }
}
