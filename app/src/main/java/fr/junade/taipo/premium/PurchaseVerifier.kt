package fr.junade.taipo.premium

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Vérification locale d'un achat Google Play (21/11/2026) : Google Play signe chaque achat (le JSON `Purchase.originalJson`) avec
 * la clé privée de l'application ; la signature (`Purchase.signature`) se vérifie avec la clé publique RSA donnée par la Play
 * Console (Monétiser > Configuration de la monétisation > Licences, « Clé publique encodée en base64 »).
 *
 * Sans cette vérification, une fausse bibliothèque Google Play (outils de contournement des achats, sur un appareil rooté) peut
 * renvoyer un achat inventé que le clavier prendrait pour vrai. Il n'y a pas de serveur : c'est la protection minimale recommandée
 * par Google pour une application sans serveur ; une vérification côté serveur (API Google Play Developer) resterait plus sûre.
 *
 * Logique pure (java.security seulement), testée en JVM (`PurchaseVerifierTest`).
 */
object PurchaseVerifier {

    enum class Result {
        /** Signature valide pour ces données. */
        VALID,

        /** Signature absente, mal formée ou fausse : achat refusé. */
        INVALID,

        /** Aucune clé publique configurée dans ce build : vérification impossible. */
        NO_KEY,

        /** Clé configurée illisible (copie tronquée, mauvaise valeur) : vérification impossible. */
        BAD_KEY,
    }

    private const val KEY_ALGORITHM = "RSA"

    /** Algorithme des signatures d'achat de Google Play. */
    private const val SIGNATURE_ALGORITHM = "SHA1withRSA"

    /** Vérifie que [signature] (base64) signe [signedData] avec la clé publique [base64PublicKey] (X.509, base64). */
    fun verify(base64PublicKey: String, signedData: String, signature: String?): Result {
        val key = parseKey(base64PublicKey) ?: return if (base64PublicKey.isBlank()) Result.NO_KEY else Result.BAD_KEY
        if (signedData.isEmpty() || signature.isNullOrBlank()) return Result.INVALID
        val signatureBytes = try {
            Base64.getDecoder().decode(signature.trim())
        } catch (_: IllegalArgumentException) {
            return Result.INVALID
        }
        return try {
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(key)
            verifier.update(signedData.toByteArray(Charsets.UTF_8))
            if (verifier.verify(signatureBytes)) Result.VALID else Result.INVALID
        } catch (_: GeneralSecurityException) {
            Result.INVALID
        }
    }

    /** Clé publique RSA lue depuis sa forme base64 (blancs et retours à la ligne ignorés), ou null si vide ou illisible. */
    fun parseKey(base64PublicKey: String): PublicKey? {
        val compact = base64PublicKey.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        return try {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(compact)))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}

/**
 * Ce que l'application fait d'un achat, d'après sa vérification ([PurchaseVerifier]) et le type de build. Logique pure, testée en
 * JVM (`PurchaseVerifierTest`).
 */
object PurchaseTrust {

    enum class Decision {
        /** Achat authentique : le pack est activé. */
        TRUSTED,

        /** Achat refusé (signature fausse, autre application) : le pack n'est pas activé par cet achat. */
        REJECTED,

        /**
         * Vérification impossible (clé absente ou illisible) dans un build de publication : rien ne change, ni activation ni
         * retrait. Un build de publication sans clé est refusé à la compilation (`app/build.gradle.kts`) ; ce cas ne reste
         * possible qu'avec une clé mal copiée, et il ne doit pas retirer le pack aux vrais acheteurs.
         */
        UNKNOWN,
    }

    /**
     * [result] : vérification de la signature ; [packageMatches] : l'achat est bien celui de cette application ; [debugBuild] :
     * build debug, où l'achat est accepté sans clé (tests avec les comptes testeurs de la Play Console).
     */
    fun decide(result: PurchaseVerifier.Result, packageMatches: Boolean, debugBuild: Boolean): Decision = when {
        !packageMatches -> Decision.REJECTED
        result == PurchaseVerifier.Result.VALID -> Decision.TRUSTED
        result == PurchaseVerifier.Result.INVALID -> Decision.REJECTED
        debugBuild -> Decision.TRUSTED
        else -> Decision.UNKNOWN
    }
}
