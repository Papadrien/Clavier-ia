package fr.junade.taipo.clipboard

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Accès à l'historique du presse-papiers (story 2.9).
 *
 * Classe abstraite pour que les règles « un seul exemplaire par texte », « plafond » et
 * « expiration » vivent dans des transactions ([record], [updateText]) : deux écritures
 * simultanées ne peuvent ni dupliquer un texte ni dépasser le plafond.
 */
@Dao
abstract class ClipHistoryDao {

    /** Toutes les lignes, de la plus récente à la plus ancienne (expirées non encore purgées comprises). */
    @Query("SELECT * FROM clip_history ORDER BY copiedAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<ClipHistoryEntity>>

    @Query("SELECT id FROM clip_history WHERE text = :text LIMIT 1")
    abstract suspend fun findIdByText(text: String): Long?

    /** Insertion brute. Utiliser [record]. */
    @Insert
    abstract suspend fun insert(entity: ClipHistoryEntity): Long

    /** Supprime les lignes copiées à [threshold] ou avant (expirées). Retourne le nombre supprimé. */
    @Query("DELETE FROM clip_history WHERE copiedAt <= :threshold")
    abstract suspend fun deleteOlderThan(threshold: Long): Int

    /** Ne garde que les [keep] lignes les plus récentes. */
    @Query(
        "DELETE FROM clip_history WHERE id IN " +
            "(SELECT id FROM clip_history ORDER BY copiedAt DESC, id DESC LIMIT -1 OFFSET :keep)",
    )
    abstract suspend fun trimTo(keep: Int): Int

    /** Remonte la ligne de [text] en tête : nouvelle date de copie, si elle est plus récente. */
    @Query("UPDATE clip_history SET copiedAt = :copiedAt WHERE text = :text AND copiedAt < :copiedAt")
    abstract suspend fun touch(text: String, copiedAt: Long): Int

    @Query("UPDATE clip_history SET sensitive = 1 WHERE text = :text")
    abstract suspend fun markSensitive(text: String): Int

    /** Remplacement brut du texte de la ligne [id]. Utiliser [updateText]. */
    @Query("UPDATE clip_history SET text = :text WHERE id = :id")
    abstract suspend fun setText(id: Long, text: String): Int

    /** Supprime les autres lignes qui ont déjà ce texte (fusion des doublons). */
    @Query("DELETE FROM clip_history WHERE text = :text AND id != :id")
    abstract suspend fun deleteOthersWithText(text: String, id: Long): Int

    /** Retourne le nombre de lignes supprimées (0 si la ligne était absente). */
    @Query("DELETE FROM clip_history WHERE id = :id")
    abstract suspend fun deleteById(id: Long): Int

    /**
     * Enregistre une copie. Purge d'abord les lignes expirées ; une copie déjà expirée n'est pas
     * enregistrée. Un texte déjà présent n'est pas dupliqué : sa ligne remonte en tête si
     * [timestampKnown] (le système a donné l'heure de cette copie), sinon elle garde son heure
     * d'origine. Au-delà de [maxEntries], les plus anciennes lignes sont supprimées. Retourne vrai
     * si la copie est dans l'historique à la sortie.
     */
    @Transaction
    open suspend fun record(
        text: String,
        copiedAt: Long,
        timestampKnown: Boolean,
        sensitive: Boolean,
        nowMillis: Long,
        retentionMillis: Long,
        maxEntries: Int,
    ): Boolean {
        val threshold = nowMillis - retentionMillis
        deleteOlderThan(threshold)
        if (copiedAt <= threshold) return false
        if (findIdByText(text) != null) {
            if (timestampKnown) touch(text, copiedAt)
            if (sensitive) markSensitive(text)
            return true
        }
        insert(ClipHistoryEntity(text = text, copiedAt = copiedAt, sensitive = sensitive))
        trimTo(maxEntries)
        return findIdByText(text) != null
    }

    /**
     * Remplace le texte de la ligne [id] ; la date de copie est conservée. Si une autre ligne a déjà
     * [text], elle est fusionnée avec celle-ci (un seul exemplaire par texte). Retourne le nombre de
     * lignes modifiées (0 si la ligne était absente).
     */
    @Transaction
    open suspend fun updateText(id: Long, text: String): Int {
        val updated = setText(id, text)
        if (updated == 0) return 0 // ligne absente : on ne fusionne rien
        deleteOthersWithText(text, id)
        return updated
    }
}
