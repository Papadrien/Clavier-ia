package fr.junade.taipo.ai

import fr.junade.taipo.model.ModelFileException
import fr.junade.taipo.model.ModelLoadException
import java.io.FileNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InferenceFailureClassifierTest {

    private fun classify(t: Throwable) = InferenceFailureClassifier.classify(t)

    @Test
    fun `fichier modele introuvable ou illisible`() {
        assertEquals(InferenceFailureCause.MODEL_FILE, classify(ModelFileException("absent")))
        assertEquals(InferenceFailureCause.MODEL_FILE, classify(FileNotFoundException("x")))
    }

    @Test
    fun `memoire insuffisante, par type ou par message du moteur`() {
        assertEquals(InferenceFailureCause.OUT_OF_MEMORY, classify(OutOfMemoryError()))
        assertEquals(InferenceFailureCause.OUT_OF_MEMORY, classify(RuntimeException("Failed to allocate 3 GB")))
    }

    @Test
    fun `la memoire passe avant l incompatibilite quand le chargement echoue par manque de memoire`() {
        val load = ModelLoadException("chargement", RuntimeException("std::bad_alloc"))
        assertEquals(InferenceFailureCause.OUT_OF_MEMORY, classify(load))
    }

    @Test
    fun `echec de chargement par le moteur = modele incompatible ou corrompu`() {
        assertEquals(InferenceFailureCause.MODEL_INCOMPATIBLE, classify(ModelLoadException("x", RuntimeException("bad file"))))
    }

    @Test
    fun `texte trop long reconnu par le message du moteur`() {
        assertEquals(InferenceFailureCause.TEXT_TOO_LONG, classify(RuntimeException("Input is too long for the context window")))
    }

    @Test
    fun `la cause est cherchee dans la chaine d exceptions`() {
        val wrapped = RuntimeException("enveloppe", OutOfMemoryError())
        assertEquals(InferenceFailureCause.OUT_OF_MEMORY, classify(wrapped))
    }

    @Test
    fun `erreur inconnue donne une erreur inattendue`() {
        assertEquals(InferenceFailureCause.UNEXPECTED, classify(IllegalArgumentException("peu importe")))
    }
}
