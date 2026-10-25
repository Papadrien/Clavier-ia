package fr.junade.taipo.dictionary

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Mot du dictionnaire personnel (story 1.4).
 *
 * [normalized] (clé primaire) est la forme minuscule du mot, ce qui garantit
 * l'unicité sans tenir compte de la casse ; [word] conserve la casse choisie
 * par l'utilisateur (ex. « iPhone »).
 */
@Entity(tableName = "personal_words")
data class PersonalWordEntity(
    @PrimaryKey val normalized: String,
    val word: String,
)
