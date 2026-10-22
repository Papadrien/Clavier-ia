package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Répartition « espace entre » de la rangée : premier à gauche, dernier à droite, espace restant réparti également. */
class SpaceBetweenTest {

    private val round = RowRole.ROUND

    @Test
    fun `le premier est a gauche, le dernier a droite et les ecarts sont egaux`() {
        // 4 boutons de 40 dans 300 : 140 d'espace, 3 écarts d'environ 46,7.
        val result = SpaceBetween.distribute(300, listOf(40, 40, 40, 40), listOf(round, round, round, round), minGap = 4)
        assertEquals(0, result.lefts[0])
        assertEquals(260, result.lefts[3])
        val gaps = (1..3).map { result.lefts[it] - (result.lefts[it - 1] + 40) }
        assertTrue(gaps.maxOrNull()!! - gaps.minOrNull()!! <= 1, "écarts $gaps")
    }

    @Test
    fun `huit boutons de 40 sur 336 retrecissent un peu pour garder l ecart minimal`() {
        val natural = List(8) { 40 }
        val result = SpaceBetween.distribute(336, natural, List(8) { round }, minGap = 4)
        // 320 de boutons sur 336 : 16 d'espace pour 7 écarts, sous l'écart minimal : les boutons rétrécissent.
        assertTrue(result.widths.all { it in 30..40 })
        assertEquals(336, result.lefts[7] + result.widths[7])
        val gaps = (1..7).map { result.lefts[it] - (result.lefts[it - 1] + result.widths[it - 1]) }
        assertTrue(gaps.all { it >= 3 }, "écarts $gaps")
    }

    @Test
    fun `un element extensible prend la place entre ses voisins avec l ecart de remplissage`() {
        // Robot, bande, Corriger, emplacement de Vocal.
        val result = SpaceBetween.distribute(
            336,
            listOf(40, 0, 40, 40),
            listOf(round, RowRole.FILL, round, RowRole.FIXED),
            minGap = 4,
            fillGap = 8,
        )
        assertEquals(0, result.lefts[0])
        assertEquals(48, result.lefts[1])
        assertEquals(336 - 3 * 8 - 120, result.widths[1])
        assertEquals(336, result.lefts[3] + result.widths[3])
    }

    @Test
    fun `sans element extensible le dernier touche exactement le bord`() {
        val result = SpaceBetween.distribute(101, listOf(30, 30, 30), listOf(round, round, round), minGap = 4)
        assertEquals(71, result.lefts[2])
    }

    @Test
    fun `la puce retrecit avant les boutons ronds`() {
        val result = SpaceBetween.distribute(
            200,
            listOf(40, 40, 150, 40, 40),
            listOf(round, round, RowRole.SHRINK, round, RowRole.FIXED),
            minGap = 4,
        )
        assertEquals(40, result.widths[0])
        assertTrue(result.widths[2] < 150)
        assertEquals(200, result.lefts[4] + result.widths[4])
    }

    @Test
    fun `un seul element est a gauche et une rangee vide ne plante pas`() {
        assertEquals(0, SpaceBetween.distribute(100, listOf(40), listOf(round), minGap = 4).lefts[0])
        assertEquals(0, SpaceBetween.distribute(100, emptyList(), emptyList(), minGap = 4).lefts.size)
    }
}
