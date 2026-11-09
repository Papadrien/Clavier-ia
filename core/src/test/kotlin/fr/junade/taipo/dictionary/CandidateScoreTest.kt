package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ordre des priorités du score unique des candidats (phase 2 de la refonte) : même hiérarchie que l'ancienne cascade. */
class CandidateScoreTest {

    private fun score(
        frequency: Long = 1_000L,
        cost: Int = 2,
        swap: Boolean = false,
        personal: Boolean = false,
        habit: HabitScore = HabitScore.NONE,
    ) = CandidateScore.of(frequency, cost, swap, personal, habit)

    @Test
    fun `le mot personnel passe devant les habitudes les plus fortes`() {
        val strongHabit = HabitScore(accepted = 1_000, context = 100_000, usage = 1_000)
        assertTrue(score(personal = true, cost = 60, frequency = 1) > score(habit = strongHabit, cost = 0, frequency = 10_000_000_000_000L))
    }

    @Test
    fun `une paire acceptee passe devant le contexte qui passe devant l'usage`() {
        assertTrue(score(habit = HabitScore(2, 0, 0)) > score(habit = HabitScore(0, 100_000, 1_000_000)))
        assertTrue(score(habit = HabitScore(0, 1, 0)) > score(habit = HabitScore(0, 0, 1_000_000)))
    }

    @Test
    fun `une habitude passe devant la proximite des touches`() {
        assertTrue(score(habit = HabitScore(0, 0, 1), cost = 60) > score(cost = 0))
    }

    @Test
    fun `la proximite des touches passe devant n'importe quelle difference de frequence reelle`() {
        assertTrue(score(cost = 2, frequency = 100) > score(cost = 4, frequency = 10_000_000_000L))
    }

    @Test
    fun `une inversion l'emporte sur un mot jusqu'a 50 fois plus frequent mais pas au-dela`() {
        assertTrue(score(swap = true, frequency = 1_000) > score(frequency = 40_000))
        assertTrue(score(swap = true, frequency = 1_000) < score(frequency = 100_000))
    }

    @Test
    fun `a termes egaux le plus frequent gagne et deux candidats identiques sont ex aequo`() {
        assertTrue(score(frequency = 2_000) > score(frequency = 1_000))
        assertEquals(score(), score())
    }

    @Test
    fun `sans habitude le score ne depend que de la frequence du cout et de l'inversion`() {
        assertEquals(score(habit = HabitScore.NONE), score(habit = HabitScore(0, 0, 0)))
    }
}
