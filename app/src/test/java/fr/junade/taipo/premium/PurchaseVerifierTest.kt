package fr.junade.taipo.premium

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Vérification des achats du pack premium (21/11/2026) : un achat signé par la clé de l'application est accepté ; un achat inventé,
 * modifié ou signé par une autre clé est refusé ; sans clé, un build de publication ne décide rien.
 */
class PurchaseVerifierTest {

    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val otherKeys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

    private val purchaseJson =
        """{"orderId":"GPA.1234-5678","packageName":"fr.junade.taipo","productId":"pass_premium","purchaseState":0,"purchaseToken":"abc"}"""

    private fun sign(data: String, pair: KeyPair = keys): String {
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(pair.private)
        signer.update(data.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    @Test
    fun `un achat signe par Google Play est valide`() {
        assertEquals(PurchaseVerifier.Result.VALID, PurchaseVerifier.verify(publicKey, purchaseJson, sign(purchaseJson)))
    }

    @Test
    fun `la cle copiee avec des retours a la ligne reste lisible`() {
        val wrapped = publicKey.chunked(64).joinToString("\n", postfix = "\n")
        assertEquals(PurchaseVerifier.Result.VALID, PurchaseVerifier.verify(wrapped, purchaseJson, sign(purchaseJson)))
    }

    @Test
    fun `un achat modifie apres signature est refuse`() {
        val signature = sign(purchaseJson)
        val forged = purchaseJson.replace("GPA.1234-5678", "GPA.0000-0000")
        assertEquals(PurchaseVerifier.Result.INVALID, PurchaseVerifier.verify(publicKey, forged, signature))
    }

    @Test
    fun `un achat signe par une autre cle est refuse`() {
        assertEquals(PurchaseVerifier.Result.INVALID, PurchaseVerifier.verify(publicKey, purchaseJson, sign(purchaseJson, otherKeys)))
    }

    @Test
    fun `une signature absente ou mal formee est refusee`() {
        assertEquals(PurchaseVerifier.Result.INVALID, PurchaseVerifier.verify(publicKey, purchaseJson, null))
        assertEquals(PurchaseVerifier.Result.INVALID, PurchaseVerifier.verify(publicKey, purchaseJson, ""))
        assertEquals(PurchaseVerifier.Result.INVALID, PurchaseVerifier.verify(publicKey, purchaseJson, "pas du base64 !"))
        assertEquals(PurchaseVerifier.Result.INVALID, PurchaseVerifier.verify(publicKey, purchaseJson, "AAAA"))
        assertEquals(PurchaseVerifier.Result.INVALID, PurchaseVerifier.verify(publicKey, "", sign("")))
    }

    @Test
    fun `sans cle ou avec une cle illisible la verification est impossible`() {
        assertEquals(PurchaseVerifier.Result.NO_KEY, PurchaseVerifier.verify("", purchaseJson, sign(purchaseJson)))
        assertEquals(PurchaseVerifier.Result.NO_KEY, PurchaseVerifier.verify("  \n", purchaseJson, sign(purchaseJson)))
        assertEquals(PurchaseVerifier.Result.BAD_KEY, PurchaseVerifier.verify("MIIBIjANBgkq", purchaseJson, sign(purchaseJson)))
        assertEquals(PurchaseVerifier.Result.BAD_KEY, PurchaseVerifier.verify(publicKey.dropLast(10), purchaseJson, sign(purchaseJson)))
    }

    @Test
    fun `lecture de la cle`() {
        assertNotNull(PurchaseVerifier.parseKey(publicKey))
        assertNull(PurchaseVerifier.parseKey(""))
        assertNull(PurchaseVerifier.parseKey("@@@"))
    }

    @Test
    fun `decision selon la verification et le build`() {
        val valid = PurchaseVerifier.Result.VALID
        val invalid = PurchaseVerifier.Result.INVALID
        val noKey = PurchaseVerifier.Result.NO_KEY
        val badKey = PurchaseVerifier.Result.BAD_KEY
        // Publication : seule une signature valide active le pack ; sans clé utilisable, rien n'est décidé.
        assertEquals(PurchaseTrust.Decision.TRUSTED, PurchaseTrust.decide(valid, packageMatches = true, debugBuild = false))
        assertEquals(PurchaseTrust.Decision.REJECTED, PurchaseTrust.decide(invalid, packageMatches = true, debugBuild = false))
        assertEquals(PurchaseTrust.Decision.UNKNOWN, PurchaseTrust.decide(noKey, packageMatches = true, debugBuild = false))
        assertEquals(PurchaseTrust.Decision.UNKNOWN, PurchaseTrust.decide(badKey, packageMatches = true, debugBuild = false))
        // Achat d'une autre application : toujours refusé.
        assertEquals(PurchaseTrust.Decision.REJECTED, PurchaseTrust.decide(valid, packageMatches = false, debugBuild = false))
        assertEquals(PurchaseTrust.Decision.REJECTED, PurchaseTrust.decide(noKey, packageMatches = false, debugBuild = true))
        // Debug : accepté sans clé, mais une signature fausse reste refusée.
        assertEquals(PurchaseTrust.Decision.TRUSTED, PurchaseTrust.decide(noKey, packageMatches = true, debugBuild = true))
        assertEquals(PurchaseTrust.Decision.REJECTED, PurchaseTrust.decide(invalid, packageMatches = true, debugBuild = true))
    }
}
