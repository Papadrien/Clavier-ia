package fr.junade.taipo.dictionary

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Base Room chiffrée (SQLCipher) du dictionnaire personnel (story 1.4).
 *
 * Le schéma est exporté (`app/schemas/`) : toute modification de
 * [PersonalWordEntity] impose d'incrémenter `version` et d'écrire une
 * migration (jamais de `fallbackToDestructiveMigration`, qui effacerait les
 * mots de l'utilisateur).
 */
@Database(entities = [PersonalWordEntity::class], version = 1, exportSchema = true)
abstract class PersonalDictionaryDatabase : RoomDatabase() {

    abstract fun personalWordDao(): PersonalWordDao

    companion object {
        const val NAME = "personal_dictionary.db"

        /**
         * Construit la base chiffrée avec [passphrase]. La bibliothèque native
         * SQLCipher doit être chargée avant toute ouverture. Attention :
         * [SupportOpenHelperFactory] efface le tableau [passphrase] après
         * usage, ne pas le réutiliser. [name] ne sert qu'aux tests instrumentés (lot 3.3), pour ne
         * jamais toucher à la vraie base de l'appareil.
         */
        fun create(context: Context, passphrase: ByteArray, name: String = NAME): PersonalDictionaryDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, PersonalDictionaryDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
