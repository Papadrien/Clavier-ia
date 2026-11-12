package fr.junade.taipo.storage

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException

/**
 * Audit BUG-02 : une erreur de lecture des données chiffrées (dictionnaire personnel, presse-papiers, mot suivant, habitudes de
 * correction) justifie-t-elle de tout effacer ?
 *
 * - **Oui** ([isUnrecoverable]) seulement si les données ne pourront jamais être relues : contenu ou clé altérés (étiquette
 *   GCM fausse), clé du Keystore définitivement invalidée ou irrécupérable, base qui n'est pas (ou plus) une base SQLCipher
 *   lisible avec sa clé, fichier dont le format est faux.
 * - **Non** pour tout le reste, en particulier une erreur passagère : Keystore momentanément indisponible, disque plein,
 *   erreur d'entrée-sortie, base verrouillée. Les données restent sur le disque, le clavier fonctionne sans elles jusqu'à
 *   la prochaine ouverture, qui réessaie.
 *
 * Les classes Android (`KeyPermanentlyInvalidatedException`, exceptions SQLite de SQLCipher) sont reconnues par leur nom :
 * ce module est du Kotlin pur. Toute la chaîne des causes est examinée ; une [IOException] rencontrée en premier l'emporte
 * (c'est l'accès au disque qui a échoué, pas le contenu).
 */
object StoredDataFailure {

    private val unrecoverableNames = setOf(
        "KeyPermanentlyInvalidatedException",
        "SQLiteDatabaseCorruptException",
        "SQLiteNotADatabaseException",
    )

    /** Message de SQLite (et SQLCipher) quand la clé est fausse ou le fichier n'est pas une base. */
    private const val NOT_A_DATABASE = "file is not a database"

    fun isUnrecoverable(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            when {
                current is IOException -> return false
                current is AEADBadTagException || current is BadPaddingException || current is UnrecoverableKeyException -> return true
                current is CorruptedDataException -> return true
                current.javaClass.simpleName in unrecoverableNames -> return true
                current.message?.contains(NOT_A_DATABASE, ignoreCase = true) == true -> return true
                // Autre erreur cryptographique (clé illisible, algorithme refusé) : à réessayer, le Keystore peut revenir.
                current is GeneralSecurityException -> return false
            }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
        return false
    }

    /** À lever quand le contenu déchiffré est illisible (format inconnu, fichier tronqué) : traité comme définitif. */
    class CorruptedDataException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val MAX_DEPTH = 10
}
