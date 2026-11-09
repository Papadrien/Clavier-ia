package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Décision de déclenchement (phase 3) : type de faute, seuil de marge, trois erreurs sur les mots longs. */
class CorrectionConfidenceTest {

    private val standard = CorrectionConfidence.STANDARD

    @Test
    fun `le type de faute se lit dans la distance, la longueur et le cout`() {
        assertEquals(FaultKind.SWAP, standard.kindOf("teh", "the", 1, true, 2))
        assertEquals(FaultKind.MISSING, standard.kindOf("oubier", "oublier", 1, false, 2))
        assertEquals(FaultKind.EXTRA, standard.kindOf("chatt", "chat", 1, false, 2))
        assertEquals(FaultKind.NEIGHBOR, standard.kindOf("cgat", "chat", 1, false, 2))
        assertEquals(FaultKind.SUBSTITUTION, standard.kindOf("maintenent", "maintenant", 1, false, 4))
        assertEquals(FaultKind.TWO_EDITS, standard.kindOf("abcde", "abxye", 2, false, 4))
        assertEquals(FaultKind.THREE_EDITS, standard.kindOf("abcdefgh", "abxyzfgh", 3, false, 6))
    }

    @Test
    fun `une egalite stricte ne corrige jamais, meme sans seuil`() {
        FaultKind.values().forEach { kind ->
            assertFalse(CorrectionConfidence.NONE.accepts(kind, 0.0, 2), "NONE : $kind")
            assertFalse(standard.accepts(kind, 0.0, 2), "STANDARD : $kind")
        }
    }

    @Test
    fun `sans seuil toute marge positive suffit comme avant la phase 3`() {
        FaultKind.values().forEach { kind ->
            assertTrue(CorrectionConfidence.NONE.accepts(kind, 1e-9, 2), "NONE : $kind")
        }
        assertFalse(CorrectionConfidence.NONE.allowsThreeEditsFor(40))
    }

    @Test
    fun `la marge doit atteindre le seuil du type de faute`() {
        val threshold = standard.thresholdOf(FaultKind.NEIGHBOR)
        assertTrue(threshold > 0.0)
        assertFalse(standard.accepts(FaultKind.NEIGHBOR, threshold - 0.01, 2))
        assertTrue(standard.accepts(FaultKind.NEIGHBOR, threshold, 2))
    }

    @Test
    fun `l'inversion demande plus de marge qu'une lettre oubliee, et trois erreurs bien plus`() {
        assertTrue(standard.thresholdOf(FaultKind.SWAP) > standard.thresholdOf(FaultKind.MISSING))
        assertTrue(standard.thresholdOf(FaultKind.THREE_EDITS) > standard.thresholdOf(FaultKind.TWO_EDITS))
    }

    @Test
    fun `aucun rival passe toujours le seuil`() {
        FaultKind.values().forEach { kind ->
            assertTrue(standard.accepts(kind, Double.POSITIVE_INFINITY, 2), "$kind")
        }
    }

    @Test
    fun `trois erreurs sont refusees si le cout de saisie depasse la limite`() {
        assertTrue(standard.accepts(FaultKind.THREE_EDITS, Double.POSITIVE_INFINITY, standard.longWordMaxCost))
        assertFalse(standard.accepts(FaultKind.THREE_EDITS, Double.POSITIVE_INFINITY, standard.longWordMaxCost + 1))
    }

    @Test
    fun `trois erreurs ne sont tentees que sur les mots longs`() {
        assertFalse(standard.allowsThreeEditsFor(standard.longWordMinLength - 1))
        assertTrue(standard.allowsThreeEditsFor(standard.longWordMinLength))
    }
}
