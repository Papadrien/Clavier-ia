package fr.junade.taipo.storage

import java.io.IOException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Audit BUG-02 : seules les erreurs définitives justifient d'effacer les données de l'utilisateur. */
class StoredDataFailureTest {

    // Mêmes noms que les classes Android / SQLCipher, reconnues par leur nom.
    private class KeyPermanentlyInvalidatedException : Exception()
    private class SQLiteDatabaseCorruptException : RuntimeException()
    private class SQLiteException(message: String) : RuntimeException(message)

    @Test
    fun `contenu ou cle alteres sont definitifs`() {
        assertTrue(StoredDataFailure.isUnrecoverable(AEADBadTagException("tag mismatch")))
        assertTrue(StoredDataFailure.isUnrecoverable(UnrecoverableKeyException()))
        assertTrue(StoredDataFailure.isUnrecoverable(KeyPermanentlyInvalidatedException()))
        assertTrue(StoredDataFailure.isUnrecoverable(SQLiteDatabaseCorruptException()))
        assertTrue(StoredDataFailure.isUnrecoverable(SQLiteException("file is not a database (code 26)")))
        assertTrue(StoredDataFailure.isUnrecoverable(StoredDataFailure.CorruptedDataException("format inconnu")))
    }

    @Test
    fun `la cause est examinee`() {
        assertTrue(StoredDataFailure.isUnrecoverable(RuntimeException("ouverture", AEADBadTagException())))
        assertTrue(StoredDataFailure.isUnrecoverable(IllegalStateException(SQLiteException("file is not a database"))))
    }

    @Test
    fun `les erreurs passageres ne sont pas definitives`() {
        assertFalse(StoredDataFailure.isUnrecoverable(IOException("No space left on device")))
        assertFalse(StoredDataFailure.isUnrecoverable(KeyStoreException("keystore unavailable")))
        assertFalse(StoredDataFailure.isUnrecoverable(SQLiteException("database is locked")))
        assertFalse(StoredDataFailure.isUnrecoverable(IllegalStateException("inattendu")))
        // Une erreur de disque en tête de chaîne l'emporte sur ce qu'elle enveloppe.
        assertFalse(StoredDataFailure.isUnrecoverable(IOException("lecture", AEADBadTagException())))
    }
}
