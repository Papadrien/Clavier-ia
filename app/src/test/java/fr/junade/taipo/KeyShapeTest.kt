package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyShapeTest {

    private val base = 8f

    @Test
    fun `arrondi est la forme par defaut et garde le dessin d'origine`() {
        assertEquals(KeyShape.ROUNDED, KeyShape.DEFAULT)
        val shape = KeyShape.ROUNDED
        assertEquals(base, shape.cornerSize(accent = false, width = 32f, height = 44f, base = base))
        assertEquals(1f, shape.shadowFactor)
        assertEquals(KeyShape.FaceBody.FILLED, shape.body(accent = false))
        assertEquals(KeyShape.Outline.ROUNDED_RECT, shape.outline())
        assertEquals(32f, shape.faceWidth(accent = false, width = 32f, height = 44f))
        assertEquals(1f, shape.radiusScale)
        // Entrée reste une pilule, comme avant.
        assertEquals(20f, shape.cornerSize(accent = true, width = 60f, height = 40f, base = base))
    }

    @Test
    fun `les cles de stockage sont uniques et se relisent`() {
        assertEquals(KeyShape.entries.size, KeyShape.entries.map { it.storageKey }.toSet().size)
        for (shape in KeyShape.entries) assertEquals(shape, KeyShape.fromStorageKey(shape.storageKey))
        assertEquals(KeyShape.DEFAULT, KeyShape.fromStorageKey(null))
        assertEquals(KeyShape.DEFAULT, KeyShape.fromStorageKey("inconnue"))
    }

    @Test
    fun `les quatre formes simples sont gratuites, les autres reservees au pass`() {
        val free = KeyShape.entries.filter { !it.requiresPremium }
        assertEquals(listOf(KeyShape.ROUNDED, KeyShape.PILL, KeyShape.SQUARE, KeyShape.FLAT), free)
        for (shape in KeyShape.entries) {
            assertTrue(shape.isAvailable(premium = true))
            assertEquals(!shape.requiresPremium, shape.isAvailable(premium = false))
            val expected = if (shape.requiresPremium) KeyShape.DEFAULT else shape
            assertEquals(expected, KeyShape.effective(shape, premium = false))
            assertEquals(shape, KeyShape.effective(shape, premium = true))
        }
    }

    @Test
    fun `la taille des coins ne depasse jamais la moitie du plus petit cote`() {
        for (shape in KeyShape.entries) {
            for (accent in listOf(false, true)) {
                for ((w, h) in listOf(32f to 44f, 120f to 40f, 10f to 10f, 4f to 30f)) {
                    val corner = shape.cornerSize(accent, w, h, base)
                    assertTrue(corner >= 0f && corner <= minOf(w, h) / 2f, "$shape accent=$accent $w×$h : $corner")
                }
            }
        }
    }

    @Test
    fun `pilule et carre`() {
        assertEquals(16f, KeyShape.PILL.cornerSize(accent = false, width = 32f, height = 44f, base = base))
        assertEquals(2f, KeyShape.SQUARE.cornerSize(accent = false, width = 32f, height = 44f, base = base))
        assertEquals(2f, KeyShape.SQUARE.cornerSize(accent = true, width = 60f, height = 40f, base = base)) // Entrée suit la forme
    }

    @Test
    fun `rond dessine un cercle sur les touches etroites, une pilule sur les touches larges et Entree`() {
        val round = KeyShape.ROUND
        assertTrue(round.isCircle(accent = false, width = 32f, height = 44f))
        assertEquals(32f, round.faceWidth(accent = false, width = 32f, height = 44f))
        assertEquals(32f, round.faceHeight(accent = false, width = 32f, height = 44f))
        assertEquals(16f, round.cornerSize(accent = false, width = 32f, height = 32f, base = base))
        assertFalse(round.isCircle(accent = false, width = 120f, height = 44f)) // espace
        assertEquals(120f, round.faceWidth(accent = false, width = 120f, height = 44f))
        assertFalse(round.isCircle(accent = true, width = 44f, height = 44f)) // Entrée
        assertFalse(KeyShape.ROUNDED.isCircle(accent = false, width = 32f, height = 44f))
    }

    @Test
    fun `biseau coupe les coins, mecanique a un flanc epais et un plateau`() {
        assertEquals(KeyShape.Outline.CHAMFER, KeyShape.BEVEL.outline())
        assertEquals(7f, KeyShape.BEVEL.cornerSize(accent = false, width = 32f, height = 44f, base = base))
        assertEquals(KeyShape.MECHANICAL_SHADOW_FACTOR, KeyShape.MECHANICAL.shadowFactor)
        assertTrue(KeyShape.MECHANICAL.hasPlateau)
        assertFalse(KeyShape.ROUNDED.hasPlateau)
    }

    @Test
    fun `plat, contour et sans fond n'ont pas d'ombre et marquent l'appui par la couleur`() {
        for (shape in listOf(KeyShape.FLAT, KeyShape.OUTLINE, KeyShape.BARE)) {
            assertEquals(0f, shape.shadowFactor)
            assertTrue(shape.pressTintsFace)
        }
        assertFalse(KeyShape.ROUNDED.pressTintsFace)
        assertEquals(KeyShape.FaceBody.OUTLINED, KeyShape.OUTLINE.body(accent = false))
        assertEquals(KeyShape.FaceBody.BARE, KeyShape.BARE.body(accent = false))
        // Entrée reste pleine pour être trouvée d'un coup d'œil.
        assertEquals(KeyShape.FaceBody.FILLED, KeyShape.OUTLINE.body(accent = true))
        assertEquals(KeyShape.FaceBody.FILLED, KeyShape.BARE.body(accent = true))
    }
}
