package fr.junade.taipo.clipboard

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Élément épinglé (story 2.5), stocké dans la base chiffrée du presse-papiers.
 *
 * [label] (étiquette, 15 caractères max) n'est pas utilisée avant la story 2.7 : la colonne existe
 * dès maintenant pour éviter une migration.
 */
@Entity(tableName = "pinned_clips")
data class PinnedClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val pinnedAt: Long,
    val label: String? = null,
)
