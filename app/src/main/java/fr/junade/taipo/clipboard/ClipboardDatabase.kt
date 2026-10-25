package fr.junade.taipo.clipboard

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Base Room chiffrée (SQLCipher) du presse-papiers (story 2.5), séparée de celle du dictionnaire
 * personnel : chacune a sa clé et ses migrations. Elle contient les éléments épinglés (table
 * `pinned_clips`, version 1) et l'historique des copies récentes (table `clip_history`, story 2.9,
 * ajoutée en version 2 par [MIGRATION_1_2]).
 *
 * Le schéma est exporté (`app/schemas/`) : toute modification d'une entité impose d'incrémenter
 * `version` et d'écrire une migration (jamais de `fallbackToDestructiveMigration`).
 */
@Database(entities = [PinnedClipEntity::class, ClipHistoryEntity::class], version = 2, exportSchema = true)
abstract class ClipboardDatabase : RoomDatabase() {

    abstract fun pinnedClipDao(): PinnedClipDao

    abstract fun clipHistoryDao(): ClipHistoryDao

    companion object {
        const val NAME = "clipboard.db"

        /** Préférences contenant la clé chiffrée (`clipboard_key.xml`) : exclues des sauvegardes. */
        const val KEY_PREFS_NAME = "clipboard_key"

        /** Alias de la clé Android Keystore qui chiffre la clé de la base. */
        const val KEY_ALIAS = "taipo_clipboard_key"

        /**
         * Story 2.9, première migration : ajoute `clip_history`. Le DDL doit correspondre exactement
         * au schéma que Room déduit de [ClipHistoryEntity] (noms, types, NOT NULL, clé primaire
         * auto-incrémentée, aucun index, aucune valeur par défaut) : sinon Room refuse la base et
         * [ClipboardProvider] la supprime, éléments épinglés compris. Vérifier avec `app/schemas/2.json`.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `clip_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`copiedAt` INTEGER NOT NULL, " +
                        "`sensitive` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * Construit la base chiffrée avec [passphrase] (effacée par SQLCipher après usage). [name] ne
         * sert qu'aux tests instrumentés (lot 3.3), pour ne jamais toucher à la vraie base de l'appareil.
         */
        fun create(context: Context, passphrase: ByteArray, name: String = NAME): ClipboardDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, ClipboardDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
