package fr.junade.taipo

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Styles d'animation ([MotionStyle]) : ressorts, étirement de Gelée, réservation au pack et effets sur [SpringValue] et [BarReveal]. */
class MotionStyleTest {

    @AfterEach
    fun reset() {
        MotionStyle.current = MotionStyle.DEFAULT
    }

    /** Plus grande valeur prise par un ressort [SpringValue] de [style] lâché de [start] vers 1, et temps pour se poser (s). */
    private fun run(style: MotionStyle, start: Float = 0f): Pair<Float, Float> {
        val spring = SpringValue(start, style)
        var peak = start
        var time = 0f
        while (!spring.isSettled(1f) && time < 3f) {
            spring.step(1f, FRAME_S)
            time += FRAME_S
            peak = maxOf(peak, spring.value)
        }
        return peak to time
    }

    @Test
    fun `ressort depasse sa cible d environ 20 pour cent`() {
        val (peak, _) = run(MotionStyle.SPRING)
        assertTrue(peak in 1.17f..1.24f, "dépassement $peak")
    }

    @Test
    fun `vif ne depasse jamais et se pose plus vite que ressort`() {
        val (peak, time) = run(MotionStyle.SNAPPY)
        val (_, springTime) = run(MotionStyle.SPRING)
        assertTrue(peak <= 1.001f, "dépassement $peak")
        assertTrue(time < springTime / 2f, "vif $time s, ressort $springTime s")
    }

    @Test
    fun `gelee rebondit davantage que ressort`() {
        assertTrue(run(MotionStyle.JELLY).first > run(MotionStyle.SPRING).first + 0.05f)
    }

    @Test
    fun `seule gelee deforme la vue`() {
        for (style in listOf(MotionStyle.SPRING, MotionStyle.SNAPPY)) {
            assertEquals(1f, style.stretchX(1.2f))
            assertEquals(1f, style.stretchY(0.5f))
        }
        val jelly = MotionStyle.JELLY
        assertTrue(jelly.stretchX(1.2f) > 1f && jelly.stretchY(1.2f) < 1f, "plus large et plus plate en dépassant")
        assertTrue(jelly.stretchX(0.5f) < 1f && jelly.stretchY(0.5f) > 1f, "plus étroite et plus haute avant d'arriver")
        assertEquals(1f, jelly.stretchX(1f))
        assertTrue(jelly.stretchX(-5f) > 0f, "jamais une taille nulle")
    }

    @Test
    fun `la reponse analytique suit le style`() {
        val spring = (0..100).maxOf { MotionStyle.SPRING.response(it / 100f, MotionStyle.SPRING.revealZeta) }
        assertTrue(spring in 1.14f..1.18f, "barre en ressort : $spring")
        assertTrue((0..100).all { MotionStyle.SNAPPY.response(it / 100f) <= 1f })
        assertEquals(0f, MotionStyle.SNAPPY.response(0f), 1e-6f)
        assertEquals(1f, MotionStyle.SNAPPY.response(1f), 1e-3f)
    }

    @Test
    fun `la barre suit le style applique`() {
        MotionStyle.current = MotionStyle.SNAPPY
        assertEquals(MotionStyle.SNAPPY.revealDurationMs, BarReveal.springDurationMs)
        assertTrue((0..100).all { BarReveal.springProgress(it / 100f) <= 1f })
        assertEquals(1f, BarReveal.springProgress(1f), 1e-3f)
        MotionStyle.current = MotionStyle.SPRING
        assertTrue((0..100).any { BarReveal.springProgress(it / 100f) > 1.1f })
    }

    @Test
    fun `un ressort sans style impose suit le style du clavier`() {
        MotionStyle.current = MotionStyle.SNAPPY
        val spring = SpringValue()
        var peak = 0f
        repeat(60) {
            spring.step(1f, FRAME_S)
            peak = maxOf(peak, spring.value)
        }
        assertTrue(peak <= 1.001f, "dépassement $peak")
    }

    @Test
    fun `gelee est reservee au pack premium`() {
        assertEquals(MotionStyle.SPRING, MotionStyle.effective(MotionStyle.JELLY, premium = false))
        assertEquals(MotionStyle.JELLY, MotionStyle.effective(MotionStyle.JELLY, premium = true))
        assertEquals(MotionStyle.SNAPPY, MotionStyle.effective(MotionStyle.SNAPPY, premium = false))
    }

    @Test
    fun `les cles de stockage se relisent et une cle inconnue donne ressort`() {
        for (style in MotionStyle.entries) assertEquals(style, MotionStyle.fromStorageKey(style.storageKey))
        assertEquals(MotionStyle.SPRING, MotionStyle.fromStorageKey(null))
        assertEquals(MotionStyle.SPRING, MotionStyle.fromStorageKey("doux"))
    }

    private companion object {
        const val FRAME_S = 1f / 60f
    }
}
