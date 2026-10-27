package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardMetricsTest {

    @Test
    fun `le portrait conserve les dimensions de reference`() {
        val m = KeyboardMetrics.forOrientation(isLandscape = false)
        assertSame(KeyboardMetrics.PORTRAIT, m)
        assertEquals(51.6f, m.rowHeightDp)
        assertEquals(60f, m.bottomMarginDp)
    }

    @Test
    fun `le paysage raccourcit les rangees et la marge basse`() {
        val portrait = KeyboardMetrics.PORTRAIT
        val landscape = KeyboardMetrics.forOrientation(isLandscape = true)
        assertSame(KeyboardMetrics.LANDSCAPE, landscape)
        assertTrue(landscape.rowHeightDp < portrait.rowHeightDp)
        assertTrue(landscape.bottomMarginDp < portrait.bottomMarginDp)
    }

    @Test
    fun `en portrait le reglage de hauteur n'est pas plafonne`() {
        KeyboardHeight.entries.forEach {
            assertEquals(it.scale, KeyboardMetrics.PORTRAIT.effectiveScale(it.scale))
        }
    }

    @Test
    fun `en paysage le reglage de hauteur est plafonne a 100 pour cent mais reste reductible`() {
        val m = KeyboardMetrics.LANDSCAPE
        assertEquals(1.0f, m.effectiveScale(KeyboardHeight.EXTRA_LARGE.scale))
        assertEquals(1.0f, m.effectiveScale(KeyboardHeight.LARGE.scale))
        assertEquals(1.0f, m.effectiveScale(KeyboardHeight.NORMAL.scale))
        assertEquals(KeyboardHeight.COMPACT.scale, m.effectiveScale(KeyboardHeight.COMPACT.scale))
    }

    @Test
    fun `la hauteur totale ajoute la marge basse aux rangees`() {
        val m = KeyboardMetrics.PORTRAIT
        assertEquals(51.6f * 4 + 60f, m.totalHeightDp(rowCount = 4, heightScale = 1.0f), 0.001f)
        assertEquals(51.6f * 1.2f * 5 + 60f, m.totalHeightDp(rowCount = 5, heightScale = 1.2f), 0.001f)
    }

    @Test
    fun `en paysage le clavier reste nettement plus bas qu'en portrait, meme avec la rangee de chiffres`() {
        val landscapeWithNumberRow = KeyboardMetrics.LANDSCAPE.totalHeightDp(5, KeyboardHeight.EXTRA_LARGE.scale)
        val portraitWithoutNumberRow = KeyboardMetrics.PORTRAIT.totalHeightDp(4, KeyboardHeight.NORMAL.scale)
        assertTrue(landscapeWithNumberRow < portraitWithoutNumberRow)
    }
}
