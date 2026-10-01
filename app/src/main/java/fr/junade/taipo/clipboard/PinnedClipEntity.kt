package fr.junade.taipo.clipboard

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Élément épinglé (story 2.5), stocké dans la base chiffrée du presse-papiers.
 *
 * [label] : étiquette de l'élément (story 2.7, 15 caractères max, null s'il n'en a pas). La colonne
 * existait dès la version 1 du schéma : aucune migration n'a été nécessaire.
 */
@Entity(tableName = "pinned_clips")
data class PinnedClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val pinnedAt: Long,
    val label: String? = null,
)
