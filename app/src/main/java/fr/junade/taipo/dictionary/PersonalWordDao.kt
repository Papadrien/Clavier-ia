package fr.junade.taipo.dictionary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Accès aux mots du dictionnaire personnel (story 1.4).
 *
 * Classe abstraite (et non interface) pour que [insertBounded] porte la règle
 * « unicité + plafond » dans une seule transaction : deux ajouts simultanés ne
 * peuvent pas dépasser [PersonalDictionary.MAX_WORDS] ni créer de doublon.
 */
@Dao
abstract class PersonalWordDao {

    /** Tous les mots ; réémet à chaque modification de la table. */
    @Query("SELECT * FROM personal_words")
    abstract fun observeAll(): Flow<List<PersonalWordEntity>>

    @Query("SELECT COUNT(*) FROM personal_words")
    abstract suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM personal_words WHERE normalized = :normalized)")
    abstract suspend fun exists(normalized: String): Boolean

    /** Insertion brute : échoue si la clé existe déjà. Utiliser [insertBounded]. */
    @Insert
    abstract suspend fun insert(entity: PersonalWordEntity)

    /** Retourne le nombre de lignes supprimées (0 si le mot était absent). */
    @Query("DELETE FROM personal_words WHERE normalized = :normalized")
    abstract suspend fun deleteByNormalized(normalized: String): Int

    @Transaction
    open suspend fun insertBounded(entity: PersonalWordEntity, maxWords: Int): PersonalDictionary.AddResult {
        if (exists(entity.normalized)) return PersonalDictionary.AddResult.ALREADY_PRESENT
        if (count() >= maxWords) return PersonalDictionary.AddResult.FULL
        insert(entity)
        return PersonalDictionary.AddResult.ADDED
    }
}
