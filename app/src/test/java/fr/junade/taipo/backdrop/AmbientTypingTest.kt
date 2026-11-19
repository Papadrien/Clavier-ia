package fr.junade.taipo.backdrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Frappe simulée du fond animé pendant une action IA (19/11/2026). */
class AmbientTypingTest {

    @Test
    fun `la premiere touche tombe tout de suite`() {
        assertEquals(1, AmbientTyping().advance(0f) { _, _ -> })
    }

    @Test
    fun `le rythme est celui d une frappe soutenue`() {
        val typing = AmbientTyping(seed = 42L)
        var presses = 0
        repeat(600) { presses += typing.advance(1f / 60f) { _, _ -> } } // 10 s à 60 images par seconde
        // Entre 1 / MAX_INTERVAL et 1 / MIN_INTERVAL touches par seconde.
        assertTrue(presses in 38..84, "touches en 10 s : $presses")
    }

    @Test
    fun `les positions restent sur la zone des touches`() {
        val typing = AmbientTyping(seed = 7L)
        repeat(300) {
            typing.advance(0.05f) { x, y ->
                assertTrue(x in 0f..1f && y in 0f..1f, "($x, $y)")
            }
        }
    }

    @Test
    fun `une trame tres en retard ne declenche pas de rafale`() {
        val typing = AmbientTyping()
        typing.advance(0f) { _, _ -> }
        assertTrue(typing.advance(5f) { _, _ -> } <= AmbientTyping.MAX_PRESSES_PER_STEP)
        // Pas de retard accumulé : la trame suivante, courte, ne rattrape pas les touches manquées.
        assertTrue(typing.advance(0.01f) { _, _ -> } <= 1)
    }
}
