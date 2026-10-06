package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CursorStepsTest {

    private fun allowed(requested: Int, before: String, after: String, selection: Boolean = false) =
        CursorSteps.allowed(requested, before, after, selection)

    @Test
    fun `au milieu du texte tous les pas sont autorises`() {
        assertEquals(-3, allowed(-3, "bonjour", "monde"))
        assertEquals(3, allowed(3, "bonjour", "monde"))
    }

    @Test
    fun `au debut du texte aucun pas vers la gauche`() {
        assertEquals(0, allowed(-1, "", "bonjour"))
        assertEquals(0, allowed(-4, "", "bonjour"))
        // Vers la droite, on peut toujours avancer.
        assertEquals(2, allowed(2, "", "bonjour"))
    }

    @Test
    fun `a la fin du texte aucun pas vers la droite`() {
        assertEquals(0, allowed(1, "bonjour", ""))
        assertEquals(0, allowed(5, "bonjour", ""))
    }

    @Test
    fun `les pas sont limites aux caracteres restants`() {
        assertEquals(-2, allowed(-5, "ab", "cdef"))
        assertEquals(3, allowed(7, "ab", "cde"))
    }

    @Test
    fun `un emoji compose compte pour un seul pas`() {
        val grin = "\uD83D\uDE00" // 😀 : deux unités UTF-16
        assertEquals(-2, allowed(-5, grin + grin, ""))
        assertEquals(2, allowed(5, "", grin + grin))
        val womanTech = "\uD83D\uDC69\u200D\uD83D\uDCBB" // 👩‍💻
        assertEquals(-1, allowed(-3, womanTech, "x"))
    }

    @Test
    fun `avec une selection, le premier pas qui la replie est toujours autorise`() {
        // Sélection collée au début du texte : un pas gauche replie la sélection, sans quitter le champ.
        assertEquals(-1, allowed(-3, "", "bonjour", selection = true))
        assertEquals(1, allowed(3, "bonjour", "", selection = true))
        // Sélection au milieu : on peut faire le pas de repli puis les caractères restants.
        assertEquals(-3, allowed(-3, "ab", "cd", selection = true))
    }

    @Test
    fun `zero pas demande, zero pas autorise`() {
        assertEquals(0, allowed(0, "abc", "def"))
    }

    @Test
    fun `la limite de lecture est proportionnelle au nombre de pas`() {
        assertEquals(0, CursorSteps.fetchLimit(0))
        assertEquals(CursorSteps.fetchLimit(2), CursorSteps.fetchLimit(-2))
        assertEquals(true, CursorSteps.fetchLimit(3) > CursorSteps.fetchLimit(1))
    }
}
