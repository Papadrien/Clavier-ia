package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ActiveModelAfterDeletionTest {

    private val all = AiModel.entriesOrdered()

    @Test
    fun `supprimer un modele non actif ne change pas l actif`() {
        assertEquals(AiModel.LEGER, ActiveModelAfterDeletion.choose(AiModel.PERFORMANT, AiModel.LEGER, all))
    }

    @Test
    fun `supprimer le modele actif passe au premier modele restant`() {
        val installed = listOf(AiModel.LEGER, AiModel.EQUILIBRE)
        assertEquals(AiModel.LEGER, ActiveModelAfterDeletion.choose(AiModel.EQUILIBRE, AiModel.EQUILIBRE, installed))
    }

    @Test
    fun `supprimer le dernier modele ne laisse aucun actif`() {
        assertNull(ActiveModelAfterDeletion.choose(AiModel.LEGER, AiModel.LEGER, listOf(AiModel.LEGER)))
    }

    @Test
    fun `sans modele actif rien ne change`() {
        assertNull(ActiveModelAfterDeletion.choose(AiModel.LEGER, null, listOf(AiModel.LEGER, AiModel.PERFORMANT)))
    }
}
