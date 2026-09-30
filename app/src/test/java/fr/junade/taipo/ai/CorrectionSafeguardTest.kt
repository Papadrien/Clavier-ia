package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CorrectionSafeguardTest {

    @Test
    fun `une correction normale est acceptee`() {
        assertEquals(
            "Je vais à la plage avec mes amis.",
            CorrectionSafeguard.accept("Je vais a la plage avec mes amis.", "Je vais à la plage avec mes amis."),
        )
    }

    @Test
    fun `les espaces en bordure de la reponse sont retires`() {
        assertEquals("Bonjour", CorrectionSafeguard.accept("Bonjour", "  Bonjour\n"))
    }

    @Test
    fun `une reponse vide est rejetee`() {
        assertNull(CorrectionSafeguard.accept("Bonjour tout le monde", ""))
        assertNull(CorrectionSafeguard.accept("Bonjour tout le monde", "  \n "))
    }

    @Test
    fun `une reponse tronquee est rejetee`() {
        val original = "Premier paragraphe assez long pour etre teste. Deuxieme phrase du meme texte."
        assertNull(CorrectionSafeguard.accept(original, "Premier paragraphe assez long pour etre teste."))
    }

    @Test
    fun `un texte court peut changer beaucoup`() {
        assertEquals("Salut", CorrectionSafeguard.accept("Salu", "Salut"))
        assertEquals("Ok", CorrectionSafeguard.accept("Okkkkkkk", "Ok"))
    }

    @Test
    fun `une reponse qui perd des retours a la ligne est rejetee`() {
        assertNull(CorrectionSafeguard.accept("Un.\n\nDeux.", "Un. Deux."))
    }

    @Test
    fun `une reponse qui garde les retours a la ligne est acceptee`() {
        assertEquals("Un.\n\nDeux.", CorrectionSafeguard.accept("Un.\n\nDeus.", "Un.\n\nDeux."))
    }
}
