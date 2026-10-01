package fr.junade.taipo.clipboard

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Ligne de l'historique du presse-papiers (story 2.9), stockée dans la base chiffrée.
 *
 * [copiedAt] : moment de la copie (ms), point de départ de la rétention d'1 h. [sensitive] : la
 * copie avait été signalée « sensible » par l'application source. Elle est stockée comme les
 * autres (la base est chiffrée), mais le drapeau est conservé pour que le panneau continue d'en
 * masquer l'aperçu et d'en refuser l'épinglage.
 *
 * Table ajoutée par la migration 1 → 2 ([ClipboardDatabase.MIGRATION_1_2]) : son DDL doit rester
 * identique au schéma que Room attend pour cette classe.
 */
@Entity(tableName = "clip_history")
data class ClipHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val copiedAt: Long,
    val sensitive: Boolean = false,
)
