package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AiModelTest {

    @Test
    fun `taille plausible ne déclenche pas d avertissement`() {
        val plausibleSize = (AiModel.ULTRA_LEGER.approxSizeBytesMin + AiModel.ULTRA_LEGER.approxSizeBytesMax) / 2
        assertNull(AiModel.ULTRA_LEGER.sizeWarning(plausibleSize))
    }

    @Test
    fun `taille trop petite declenche un avertissement`() {
        val tooSmall = AiModel.EQUILIBRE.approxSizeBytesMin - 1
        assertNotNull(AiModel.EQUILIBRE.sizeWarning(tooSmall))
    }

    @Test
    fun `taille trop grande declenche un avertissement`() {
        val tooBig = AiModel.PERFORMANT.approxSizeBytesMax + 1
        assertNotNull(AiModel.PERFORMANT.sizeWarning(tooBig))
    }

    @Test
    fun `taille inconnue declenche un avertissement`() {
        assertNotNull(AiModel.LEGER.sizeWarning(-1L))
    }

    @Test
    fun `byId retrouve le bon modele`() {
        assertNotNull(AiModel.byId("equilibre"))
        assertNull(AiModel.byId("inconnu"))
    }

    @Test
    fun `l ordre d affichage des parametres est l inverse de l ordre logique`() {
        assertEquals(AiModel.entriesOrdered().reversed(), AiModel.entriesForDisplay())
        assertEquals(AiModel.PERFORMANT, AiModel.entriesForDisplay().first())
        assertEquals(AiModel.ULTRA_LEGER, AiModel.entriesOrdered().first())
    }
}
