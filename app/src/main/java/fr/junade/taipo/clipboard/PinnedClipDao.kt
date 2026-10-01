package fr.junade.taipo.clipboard

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Accès aux éléments épinglés (stories 2.5 à 2.8).
 *
 * Classe abstraite pour que [insertBounded] porte la règle « pas de doublon + plafond » dans une
 * seule transaction : deux épinglages simultanés ne peuvent pas dépasser [ClipboardItems.MAX_PINNED].
 */
@Dao
abstract class PinnedClipDao {

    /** Tous les éléments, du plus récemment épinglé au plus ancien ; réémet à chaque modification. */
    @Query("SELECT * FROM pinned_clips ORDER BY pinnedAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<PinnedClipEntity>>

    @Query("SELECT COUNT(*) FROM pinned_clips")
    abstract suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM pinned_clips WHERE text = :text)")
    abstract suspend fun existsByText(text: String): Boolean

    /** Insertion brute. Utiliser [insertBounded]. */
    @Insert
    abstract suspend fun insert(entity: PinnedClipEntity): Long

    /** Retourne le nombre de lignes supprimées (0 si l'élément était absent). */
    @Query("DELETE FROM pinned_clips WHERE id = :id")
    abstract suspend fun deleteById(id: Long): Int

    /**
     * Story 2.6 : remplace le texte de l'élément [id] ; l'ordre ([PinnedClipEntity.pinnedAt]) et
     * l'étiquette sont conservés, et un doublon avec un autre élément est autorisé. Retourne le
     * nombre de lignes modifiées (0 si l'élément était absent).
     */
    @Query("UPDATE pinned_clips SET text = :text WHERE id = :id")
    abstract suspend fun updateText(id: Long, text: String): Int

    /**
     * Stories 2.7 et 2.8 : remplace l'étiquette de l'élément [id] (null = la retirer) ; le texte et
     * l'ordre sont conservés. Retourne le nombre de lignes modifiées (0 si l'élément était absent).
     */
    @Query("UPDATE pinned_clips SET label = :label WHERE id = :id")
    abstract suspend fun updateLabel(id: Long, label: String?): Int

    @Transaction
    open suspend fun insertBounded(entity: PinnedClipEntity, maxPinned: Int): ClipboardItems.PinResult {
        if (existsByText(entity.text)) return ClipboardItems.PinResult.ALREADY_PINNED
        if (count() >= maxPinned) return ClipboardItems.PinResult.FULL
        insert(entity)
        return ClipboardItems.PinResult.PINNED
    }
}
