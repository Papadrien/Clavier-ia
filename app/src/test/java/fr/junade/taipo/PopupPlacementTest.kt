package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PopupPlacementTest {

    // Bulle de 5 colonnes de 44 px + 2 × 8 px de marge = 236 px ; le choix par défaut est dans la
    // première colonne (accents : premier choix en bas à gauche) : centre à 8 + 22 = 30 px du bord gauche.
    private val popupWidth = 236f
    private val defaultAnchor = 30f

    @Test
    fun `le choix presélectionne est centre au dessus de la touche`() {
        val left = PopupPlacement.left(
            keyCenterX = 500f, anchorInPopup = defaultAnchor, popupWidth = popupWidth, minLeft = 4f, maxRight = 1076f,
        )
        assertEquals(500f, left + defaultAnchor)
    }

    @Test
    fun `sans choix par defaut la bulle entiere est centree`() {
        val left = PopupPlacement.left(
            keyCenterX = 500f, anchorInPopup = popupWidth / 2f, popupWidth = popupWidth, minLeft = 4f, maxRight = 1076f,
        )
        assertEquals(500f, left + popupWidth / 2f)
    }

    @Test
    fun `pres du bord gauche la bulle reste visible`() {
        val left = PopupPlacement.left(
            keyCenterX = 20f, anchorInPopup = defaultAnchor, popupWidth = popupWidth, minLeft = 4f, maxRight = 1076f,
        )
        assertEquals(4f, left)
    }

    @Test
    fun `pres du bord droit la bulle reste visible`() {
        val left = PopupPlacement.left(
            keyCenterX = 1060f, anchorInPopup = defaultAnchor, popupWidth = popupWidth, minLeft = 4f, maxRight = 1076f,
        )
        assertEquals(1076f - popupWidth, left)
    }

    @Test
    fun `une zone plus etroite que la bulle ne plante pas`() {
        val left = PopupPlacement.left(
            keyCenterX = 100f, anchorInPopup = defaultAnchor, popupWidth = popupWidth, minLeft = 4f, maxRight = 100f,
        )
        assertEquals(4f, left)
    }
}
