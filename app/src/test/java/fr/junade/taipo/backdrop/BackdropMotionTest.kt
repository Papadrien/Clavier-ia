package fr.junade.taipo.backdrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackdropMotionTest {

    private fun settle(motion: BackdropMotion): Int {
        var frames = 0
        while (motion.isRunning && frames < 600) {
            motion.step(1f / 60f)
            frames++
        }
        return frames
    }

    @Test
    fun `au repos rien ne bouge`() {
        val motion = BackdropMotion()
        assertFalse(motion.isRunning)
        assertEquals(0f, motion.phase)
    }

    @Test
    fun `une touche fait deriver le motif, qui s'arrete la ou il est arrive en moins de deux secondes`() {
        val motion = BackdropMotion()
        motion.press(10f, 20f)
        assertTrue(motion.isRunning)
        val frames = settle(motion)
        assertTrue(frames < 120, "posé en $frames images")
        val reached = motion.phase
        assertTrue(reached > 0f)
        motion.step(1f)
        assertEquals(reached, motion.phase, "à l'arrêt, le motif ne bouge plus")
    }

    @Test
    fun `la frappe soutenue fait deriver plus loin qu'une seule touche`() {
        val one = BackdropMotion().apply { press(0f, 0f) }
        settle(one)
        val many = BackdropMotion()
        repeat(8) {
            many.press(0f, 0f)
            repeat(8) { many.step(1f / 60f) }
        }
        settle(many)
        assertTrue(many.phase > one.phase * 3)
    }

    @Test
    fun `l'elan est plafonne et les touches anciennes sont oubliees`() {
        val motion = BackdropMotion()
        repeat(20) { motion.press(it.toFloat(), 0f) }
        assertEquals(BackdropMotion.MAX_ENERGY, motion.energy)
        assertEquals(BackdropMotion.MAX_HITS, motion.hits.size)
    }

    @Test
    fun `seul le fond uni est gratuit`() {
        assertTrue(KeyboardBackdrop.PLAIN.isAvailable(premium = false))
        KeyboardBackdrop.entries.filter { it.isPattern }.forEach { assertFalse(it.isAvailable(premium = false), it.name) }
        assertEquals(KeyboardBackdrop.PLAIN, KeyboardBackdrop.effective(KeyboardBackdrop.CRYSTAL, premium = false))
        assertEquals(KeyboardBackdrop.CRYSTAL, KeyboardBackdrop.effective(KeyboardBackdrop.CRYSTAL, premium = true))
        KeyboardBackdrop.entries.forEach { assertEquals(it, KeyboardBackdrop.fromStorageKey(it.storageKey)) }
    }
}
