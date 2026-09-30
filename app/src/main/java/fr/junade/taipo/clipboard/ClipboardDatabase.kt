package fr.junade.taipo.clipboard

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Base Room chiffrée (SQLCipher) du presse-papiers (story 2.5), séparée de celle du dictionnaire
 * personnel : chacune a sa clé et ses migrations. Elle contient pour l'instant les éléments
 * épinglés ; l'historique des éléments non épinglés (story 2.9) s'y ajoutera.
 *
 * Le schéma est exporté (`app/schemas/`) : toute modification d'une entité impose d'incrémenter
 * `version` et d'écrire une migration (jamais de `fallbackToDestructiveMigration`).
 */
@Database(entities = [PinnedClipEntity::class], version = 1, exportSchema = true)
abstract class ClipboardDatabase : RoomDatabase() {

    abstract fun pinnedClipDao(): PinnedClipDao

    companion object {
        const val NAME = "clipboard.db"

        /** Préférences contenant la clé chiffrée (`clipboard_key.xml`) : exclues des sauvegardes. */
        const val KEY_PREFS_NAME = "clipboard_key"

        /** Alias de la clé Android Keystore qui chiffre la clé de la base. */
        const val KEY_ALIAS = "taipo_clipboard_key"

        /** Construit la base chiffrée avec [passphrase] (effacée par SQLCipher après usage). */
        fun create(context: Context, passphrase: ByteArray): ClipboardDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, ClipboardDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
