package fr.junade.taipo

import fr.junade.taipo.dictionary.NextLetterModel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/** Zones de frappe adaptatives, étape 2 : géométrie ancrée des zones de toucher d'une rangée. */
class TouchTargetsTest {

    private val eps = 1e-4f

    private fun widths(n: Int = 10) = List(n) { 1f }

    private fun width(edges: FloatArray, i: Int) = edges[i + 1] - edges[i]

    @Test
    fun `sans probabilite les zones sont nominales`() {
        val w = listOf(1.4f, 1f, 1f, 1f, 1.6f)
        val edges = TouchTargets.edges(w, List(5) { null })
        assertArrayEquals(TouchTargets.nominalEdges(w), edges, 0f)
        assertArrayEquals(floatArrayOf(0f, 1.4f, 2.4f, 3.4f, 4.4f, 6f), edges, eps)
    }

    @Test
    fun `la touche probable gagne de la place sur ses voisines`() {
        // « tim » : le « e » est très probable, ses voisines (z et r en AZERTY) ne le sont pas.
        val probabilities = List<Double?>(10) { 0.01 }.toMutableList().apply { this[1] = 0.8 }
        val edges = TouchTargets.edges(widths(), probabilities)
        assertTrue(width(edges, 1) > 1.2f, "zone du e : ${width(edges, 1)}")
        assertTrue(width(edges, 0) < 1f)
        assertTrue(width(edges, 2) < 1f)
        // Les autres touches (probabilités égales) gardent leur taille.
        assertEquals(1f, width(edges, 5), eps)
    }

    @Test
    fun `des probabilites egales ou toutes faibles ne changent presque rien`() {
        val flat = TouchTargets.edges(widths(), List(10) { 0.1 })
        assertArrayEquals(TouchTargets.nominalEdges(widths()), flat, eps)
        val faint = TouchTargets.edges(widths(), List<Double?>(10) { 0.001 }.toMutableList().apply { this[3] = 0.01 })
        // Écart de 0,009 pour un régularisateur de 0,05 : le bord bouge de moins de 5 % de la largeur d'une touche.
        assertTrue(kotlin.math.abs(faint[3] - 3f) < 0.05f)
    }

    @Test
    fun `le coeur de chaque touche lui reste quelles que soient les probabilites`() {
        val random = Random(7)
        val settings = TouchTargets.Settings(core = 0.6f)
        repeat(500) {
            val w = List(9) { 0.8f + random.nextFloat() }
            val probabilities = List<Double?>(9) { if (random.nextInt(8) == 0) null else random.nextDouble() }
            val edges = TouchTargets.edges(w, probabilities, settings)
            val nominal = TouchTargets.nominalEdges(w)
            for (i in w.indices) {
                val center = (nominal[i] + nominal[i + 1]) / 2f
                val half = settings.core * w[i] / 2f
                assertTrue(edges[i] <= center - half + eps, "cœur à gauche, touche $i")
                assertTrue(edges[i + 1] >= center + half - eps, "cœur à droite, touche $i")
            }
            for (i in 0 until w.size) assertTrue(edges[i] < edges[i + 1], "bords croissants")
        }
    }

    @Test
    fun `les bords exterieurs de la rangee ne bougent pas`() {
        val probabilities = List<Double?>(10) { 0.01 }.toMutableList().apply { this[0] = 0.9; this[9] = 0.9 }
        val edges = TouchTargets.edges(widths(), probabilities)
        assertEquals(0f, edges.first(), 0f)
        assertEquals(10f, edges.last(), eps)
    }

    @Test
    fun `un bord contre une touche non adaptative ne bouge pas`() {
        // Maj (null) à gauche d'un w très probable : le bord entre les deux reste nominal.
        val w = listOf(1.4f, 1f, 1f, 1.6f)
        val edges = TouchTargets.edges(w, listOf(null, 0.9, 0.01, null))
        val nominal = TouchTargets.nominalEdges(w)
        assertEquals(nominal[1], edges[1], 0f)
        assertEquals(nominal[3], edges[3], 0f)
        // Le bord entre les deux lettres, lui, bouge vers la moins probable.
        assertTrue(edges[2] > nominal[2])
    }

    @Test
    fun `une force nulle redonne les zones nominales`() {
        val probabilities = List<Double?>(10) { if (it == 4) 0.9 else 0.01 }
        val edges = TouchTargets.edges(widths(), probabilities, TouchTargets.Settings(strength = 0f))
        assertArrayEquals(TouchTargets.nominalEdges(widths()), edges, 0f)
    }

    @Test
    fun `un coeur de 100 pour cent supprime le redimensionnement`() {
        val probabilities = List<Double?>(10) { if (it == 4) 0.9 else 0.01 }
        val edges = TouchTargets.edges(widths(), probabilities, TouchTargets.Settings(core = 1f))
        assertArrayEquals(TouchTargets.nominalEdges(widths()), edges, eps)
    }

    @Test
    fun `la zone d une touche ne diminue jamais quand sa probabilite augmente`() {
        val others = List<Double?>(7) { 0.05 * (it + 1) }
        var previous = 0f
        for (p in listOf(0.0, 0.02, 0.1, 0.3, 0.6, 0.95, 1.0)) {
            val probabilities = others.toMutableList().apply { this[3] = p }
            val zone = width(TouchTargets.edges(widths(7), probabilities), 3)
            assertTrue(zone >= previous - eps, "p=$p : $zone < $previous")
            previous = zone
        }
    }

    @Test
    fun `la croissance est bornee par l ancrage`() {
        // Ses deux voisines étant impossibles, la touche gagne au plus (1 - cœur) de sa largeur : 0,4 pour un cœur de 0,6.
        val probabilities = List<Double?>(5) { if (it == 2) 1.0 else 0.0 }
        val edges = TouchTargets.edges(widths(5), probabilities, TouchTargets.Settings(core = 0.6f, regularizer = 1e-6f))
        assertEquals(1.4f, width(edges, 2), 1e-3f)
    }

    @Test
    fun `indexAt retrouve la touche et se cale sur les extremites`() {
        val edges = floatArrayOf(0f, 1.2f, 2f, 3f)
        assertEquals(0, TouchTargets.indexAt(edges, 0f))
        assertEquals(0, TouchTargets.indexAt(edges, 1.19f))
        assertEquals(1, TouchTargets.indexAt(edges, 1.2f))
        assertEquals(2, TouchTargets.indexAt(edges, 2.5f))
        assertEquals(0, TouchTargets.indexAt(edges, -3f))
        assertEquals(2, TouchTargets.indexAt(edges, 9f))
    }

    @Test
    fun `les reglages et les tableaux invalides sont refuses`() {
        assertThrows(IllegalArgumentException::class.java) { TouchTargets.Settings(core = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { TouchTargets.Settings(regularizer = 0f) }
        assertThrows(IllegalArgumentException::class.java) { TouchTargets.Settings(strength = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { TouchTargets.edges(widths(3), listOf(0.1, 0.2)) }
        assertThrows(IllegalArgumentException::class.java) { TouchTargets.edges(widths(2), listOf(0.1, -0.2)) }
    }

    // --- Avec les vraies rangées et le modèle de lettre suivante ---

    private val frRows = Keyboards.letters.rows

    private fun model(vararg entries: Pair<String, Long>) = NextLetterModel.fromFrequencies(mapOf(*entries))

    @Test
    fun `seules les lettres et l apostrophe sont adaptatives`() {
        val next = model("tim" to 1L, "time" to 5L, "timb" to 1L).probabilitiesAfter("tim")!!
        val thirdRow = frRows[2]
        val probabilities = TouchTargets.probabilities(thirdRow, next)
        thirdRow.forEachIndexed { i, key ->
            val char = (key.action as? KeyAction.TypeChar)?.char
            if (char != null && (char in 'a'..'z' || char == '\'')) {
                assertTrue(probabilities[i] != null, key.id)
            } else {
                assertTrue(probabilities[i] == null, "Maj, Effacer... : ${key.id}")
            }
        }
    }

    @Test
    fun `apres tim le e de la rangee du haut agrandit sa zone`() {
        val next = model("time" to 80L, "timber" to 10L, "timid" to 10L).probabilitiesAfter("tim")!!
        val row = frRows[0] // a z e r t y u i o p
        val edges = TouchTargets.rowEdges(row, next)
        val eIndex = row.indexOfFirst { (it.action as KeyAction.TypeChar).char == 'e' }
        val nominal = TouchTargets.nominalEdges(row.map { it.weight })
        assertTrue(edges[eIndex + 1] - edges[eIndex] > nominal[eIndex + 1] - nominal[eIndex] + 0.1f)
        // Un point juste à gauche du bord nominal du e (côté z) tombe maintenant sur le e.
        val x = nominal[eIndex] - 0.1f
        assertEquals(eIndex - 1, TouchTargets.indexAt(nominal, x))
        assertEquals(eIndex, TouchTargets.indexAt(edges, x))
    }

    @Test
    fun `la rangee du bas et les touches de fonction gardent leurs bords`() {
        val next = model("time" to 80L, "timber" to 10L).probabilitiesAfter("tim")!!
        val third = frRows[2] // Maj w x c v b n ' Effacer
        val edges = TouchTargets.rowEdges(third, next)
        val nominal = TouchTargets.nominalEdges(third.map { it.weight })
        assertEquals(nominal[1], edges[1], 0f) // bord Maj / w
        assertEquals(nominal[third.size - 1], edges[third.size - 1], 0f) // bord ' / Effacer
        // Bas de clavier : aucune lettre ni apostrophe, donc aucun bord ne bouge.
        val bottom = frRows.last()
        assertArrayEquals(TouchTargets.nominalEdges(bottom.map { it.weight }), TouchTargets.rowEdges(bottom, next), 0f)
    }
}
