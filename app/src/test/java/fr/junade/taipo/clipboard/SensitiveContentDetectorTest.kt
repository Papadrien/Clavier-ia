package fr.junade.taipo.clipboard

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 2.10 : détection d'un numéro de carte bancaire (le mot de passe ne se détecte pas par le contenu). */
class SensitiveContentDetectorTest {

    private fun card(text: String) = SensitiveContentDetector.looksLikeCardNumber(text)

    @Test
    fun `des numeros de test valides de chaque reseau sont detectes`() {
        assertTrue(card("4111111111111111")) // Visa
        assertTrue(card("5555555555554444")) // Mastercard
        assertTrue(card("2223003122003222")) // Mastercard série 2
        assertTrue(card("378282246310005")) // Amex, 15 chiffres
        assertTrue(card("6011111111111117")) // Discover
        assertTrue(card("3530111333300000")) // JCB
    }

    @Test
    fun `les groupes par espaces ou tirets sont detectes`() {
        assertTrue(card("4111 1111 1111 1111"))
        assertTrue(card("4111-1111-1111-1111"))
        assertTrue(card("3782 822463 10005"))
    }

    @Test
    fun `les espaces et retours a la ligne autour sont ignores`() {
        assertTrue(card("  4111 1111 1111 1111 \n"))
    }

    @Test
    fun `une cle de Luhn invalide n est pas detectee`() {
        assertFalse(card("4111111111111112"))
        assertFalse(card("4111 1111 1111 1112"))
    }

    @Test
    fun `un numero trop court ou trop long n est pas detecte`() {
        assertFalse(card("411111111111")) // 12 chiffres
        assertFalse(card("41111111111111111111")) // 20 chiffres
    }

    @Test
    fun `un premier chiffre hors des reseaux bancaires n est pas detecte`() {
        // Horodatage en millisecondes : 13 chiffres commençant par 1.
        assertFalse(card("1727800000000"))
        assertFalse(card("0000000000000000"))
    }

    @Test
    fun `un numero de telephone ou un IBAN n est pas detecte`() {
        assertFalse(card("06 12 34 56 78"))
        assertFalse(card("+33 6 12 34 56 78"))
        assertFalse(card("FR76 3000 6000 0112 3456 7890 189"))
    }

    @Test
    fun `un numero noye dans une phrase n est pas detecte`() {
        assertFalse(card("Ma carte : 4111 1111 1111 1111"))
        assertFalse(card("4111 1111 1111 1111 expire en 12/27"))
    }

    @Test
    fun `des separateurs mal places ou d autres caracteres ne sont pas acceptes`() {
        assertFalse(card("4111  1111 1111 1111")) // deux espaces d'affilée
        assertFalse(card("4111 1111 1111 1111 -"))
        assertFalse(card("-4111111111111111"))
        assertFalse(card("4111.1111.1111.1111"))
        assertFalse(card("4111 1111 1111 111a"))
    }

    @Test
    fun `un texte vide ou tres long est ecarte`() {
        assertFalse(card(""))
        assertFalse(card("   "))
        assertFalse(card("4111 ".repeat(5_000)))
    }

    @Test
    fun `le contenu d un mot de passe n est pas detecte`() {
        assertFalse(SensitiveContentDetector.looksSensitive("Xk9#mP2\$vL!q"))
        assertFalse(SensitiveContentDetector.looksSensitive("MonMotDePasse2024"))
    }

    @Test
    fun `looksSensitive suit la detection de carte`() {
        assertTrue(SensitiveContentDetector.looksSensitive("4111 1111 1111 1111"))
        assertFalse(SensitiveContentDetector.looksSensitive("Bonjour"))
    }
}
