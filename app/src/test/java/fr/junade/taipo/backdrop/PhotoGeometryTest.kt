package fr.junade.taipo.backdrop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fond « Image » (19/11/2026) : réduction à l'import, recadrage au centre, repli sur le fond uni sans image. */
class PhotoGeometryTest {

    @Test
    fun `le sous-echantillonnage garde le grand cote au-dessus de la limite`() {
        assertEquals(1, PhotoGeometry.sampleSize(1000, 800, 1440))
        assertEquals(1, PhotoGeometry.sampleSize(2000, 1500, 1440))
        assertEquals(2, PhotoGeometry.sampleSize(4000, 3000, 1440))
        assertEquals(8, PhotoGeometry.sampleSize(3000, 12000, 1440))
        assertEquals(1, PhotoGeometry.sampleSize(0, 0, 1440))
    }

    @Test
    fun `la reduction garde les proportions sans jamais agrandir`() {
        assertEquals(1440 to 1080, PhotoGeometry.fitInside(2000, 1500, 1440))
        assertEquals(810 to 1440, PhotoGeometry.fitInside(1500, 2667, 1440))
        assertEquals(800 to 600, PhotoGeometry.fitInside(800, 600, 1440))
    }

    @Test
    fun `une photo en portrait est coupee en haut et en bas pour un clavier large`() {
        // 1080 × 1920 dans un clavier 1080 × 900 : toute la largeur, bande centrale de 900 px de haut.
        assertEquals(CropRect(0, 510, 1080, 1410), PhotoGeometry.centerCrop(1080, 1920, 1080, 900))
    }

    @Test
    fun `une photo panoramique est coupee a gauche et a droite`() {
        // 3000 × 1000 dans 1200 × 1000 : toute la hauteur, 1200 px centrés.
        assertEquals(CropRect(900, 0, 2100, 1000), PhotoGeometry.centerCrop(3000, 1000, 1200, 1000))
    }

    @Test
    fun `aux memes proportions toute l image est gardee`() {
        assertEquals(CropRect(0, 0, 1440, 1200), PhotoGeometry.centerCrop(1440, 1200, 720, 600))
    }

    @Test
    fun `l image est un fond du pack, et sans image enregistree le fond reste uni`() {
        val photo = KeyboardBackdrop.PHOTO
        assertTrue(photo.isPattern)
        assertFalse(photo.isGenerated)
        assertFalse(photo.isAvailable(premium = false))
        assertEquals(photo, KeyboardBackdrop.fromStorageKey("photo"))
        assertEquals(photo, KeyboardBackdrop.effective(photo, premium = true, hasPhoto = true))
        assertEquals(KeyboardBackdrop.PLAIN, KeyboardBackdrop.effective(photo, premium = true, hasPhoto = false))
        assertEquals(KeyboardBackdrop.PLAIN, KeyboardBackdrop.effective(photo, premium = false, hasPhoto = true))
        assertEquals(KeyboardBackdrop.CRYSTAL, KeyboardBackdrop.effective(KeyboardBackdrop.CRYSTAL, premium = true, hasPhoto = false))
        assertTrue(KeyboardBackdrop.CRYSTAL.isGenerated)
    }
}
