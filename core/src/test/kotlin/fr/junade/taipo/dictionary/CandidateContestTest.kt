package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Concours des candidats (phase 3) : le meilleur, le suivant, et la marge qui mesure la confiance. */
class CandidateContestTest {

    @Test
    fun `sans candidat il n'y a ni meilleur ni marge`() {
        val contest = CandidateContest()
        assertNull(contest.best)
        assertEquals(0.0, contest.margin())
    }

    @Test
    fun `un candidat seul n'a aucun rival donc une marge infinie`() {
        val contest = CandidateContest()
        contest.offer("chat", 1, 5.0, 2, false)
        assertEquals("chat", contest.best)
        assertEquals(Double.POSITIVE_INFINITY, contest.margin())
    }

    @Test
    fun `a distance egale la marge est l'ecart des scores`() {
        val contest = CandidateContest()
        contest.offer("chut", 1, 4.0, 2, false)
        contest.offer("chat", 1, 6.5, 2, false)
        assertEquals("chat", contest.best)
        assertEquals(2.5, contest.margin(), 1e-9)
    }

    @Test
    fun `l'ordre d'arrivee ne change ni le meilleur ni la marge`() {
        val first = CandidateContest().apply {
            offer("chat", 1, 6.5, 2, false)
            offer("chut", 1, 4.0, 2, false)
            offer("chot", 1, 1.0, 2, false)
        }
        val second = CandidateContest().apply {
            offer("chot", 1, 1.0, 2, false)
            offer("chut", 1, 4.0, 2, false)
            offer("chat", 1, 6.5, 2, false)
        }
        assertEquals(first.best, second.best)
        assertEquals(first.margin(), second.margin(), 1e-9)
        assertEquals(2.5, first.margin(), 1e-9)
    }

    @Test
    fun `une egalite stricte donne une marge nulle`() {
        val contest = CandidateContest()
        contest.offer("chat", 1, 5.0, 2, false)
        contest.offer("chut", 1, 5.0, 2, false)
        assertEquals(0.0, contest.margin())
    }

    @Test
    fun `la distance passe avant le score et un rival plus loin n'ote aucune confiance`() {
        val contest = CandidateContest()
        contest.offer("loin", 2, 100.0, 4, false)
        contest.offer("pres", 1, -50.0, 2, false)
        assertEquals("pres", contest.best)
        assertEquals(1, contest.bestDistance)
        assertEquals(Double.POSITIVE_INFINITY, contest.margin())
    }

    @Test
    fun `un meilleur candidat tardif relegue l'ancien au rang de suivant`() {
        val contest = CandidateContest()
        contest.offer("chut", 1, 4.0, 2, false)
        contest.offer("chat", 1, 4.4, 2, true)
        assertEquals("chat", contest.best)
        assertTrue(contest.bestIsSwap)
        assertEquals(0.4, contest.margin(), 1e-9)
    }

    @Test
    fun `le meme mot propose deux fois garde son meilleur score et ne devient pas son propre rival`() {
        val contest = CandidateContest()
        contest.offer("adrien", 1, 3.0, 2, false)
        contest.offer("adrien", 1, 1_000_000_003.0, 2, false)
        assertEquals("adrien", contest.best)
        assertEquals(Double.POSITIVE_INFINITY, contest.margin())
    }
}
