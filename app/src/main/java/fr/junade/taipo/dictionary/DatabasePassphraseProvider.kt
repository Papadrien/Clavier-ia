package fr.junade.taipo.dictionary

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fournit la clé de chiffrement de la base du dictionnaire personnel.
 *
 * La clé (256 bits aléatoires, encodés en hexadécimal ASCII) n'est jamais
 * stockée en clair : elle est chiffrée en AES-256-GCM par une clé non
 * exportable de l'Android Keystore, et seul le résultat est conservé dans les
 * SharedPreferences. Un attaquant qui copie la base et les préférences ne peut
 * donc rien en faire hors de l'appareil.
 *
 * Ces préférences et la base sont exclues des sauvegardes (voir
 * `res/xml/backup_rules.xml` et `data_extraction_rules.xml`) : une clé
 * Keystore ne suit pas une restauration sur un autre appareil, la base
 * restaurée serait donc illisible.
 *
 * À appeler hors du thread principal (accès Keystore).
 */
class DatabasePassphraseProvider(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Retourne la clé, en la générant et en la persistant à la première
     * utilisation. Retourne toujours un nouveau tableau (à effacer par
     * l'appelant ou par SQLCipher).
     */
    @Synchronized
    fun getOrCreate(): ByteArray {
        val stored = prefs.getString(KEY_BLOB, null)
        if (stored != null) return decrypt(stored)

        val random = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val passphrase = random.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        // commit() et non apply() : la clé doit être durablement écrite avant
        // que la base ne soit chiffrée avec, sinon un arrêt du processus
        // rendrait la base créée illisible.
        check(prefs.edit().putString(KEY_BLOB, encrypt(passphrase)).commit()) {
            "Impossible d'enregistrer la clé de chiffrement du dictionnaire personnel"
        }
        return passphrase
    }

    /**
     * Oublie la clé (préférences + clé Keystore). À n'utiliser qu'avec la
     * suppression du fichier de base correspondant : sans sa clé, les données
     * sont définitivement illisibles.
     */
    @Synchronized
    fun reset() {
        prefs.edit().remove(KEY_BLOB).commit()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Format stocké : `base64(iv):base64(chiffré+tag)`. Le Keystore génère lui-même l'IV. */
    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        val encrypted = cipher.doFinal(plain)
        return encode(cipher.iv) + SEPARATOR + encode(encrypted)
    }

    private fun decrypt(stored: String): ByteArray {
        val parts = stored.split(SEPARATOR)
        require(parts.size == 2) { "Format de clé chiffrée invalide" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), GCMParameterSpec(GCM_TAG_BITS, decode(parts[0])))
        return cipher.doFinal(decode(parts[1]))
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        /** Nom de fichier de préférences exclu des sauvegardes (`personal_dictionary_key.xml`). */
        const val PREFS_NAME = "personal_dictionary_key"

        private const val KEY_BLOB = "encrypted_passphrase"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "taipo_personal_dictionary_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PASSPHRASE_BYTES = 32
        private const val SEPARATOR = ":"
    }
}
