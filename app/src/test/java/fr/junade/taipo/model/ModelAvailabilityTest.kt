package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModelAvailabilityTest {

    @Test
    fun `aucun modele actif donne aucun modele utilisable`() {
        assertNull(ModelAvailability.usable(null) { true })
    }

    @Test
    fun `un modele actif dont le fichier a disparu n est pas utilisable`() {
        assertNull(ModelAvailability.usable(AiModel.EQUILIBRE) { false })
    }

    @Test
    fun `un modele actif installe est utilisable`() {
        assertEquals(AiModel.LEGER, ModelAvailability.usable(AiModel.LEGER) { it == AiModel.LEGER })
    }
}
