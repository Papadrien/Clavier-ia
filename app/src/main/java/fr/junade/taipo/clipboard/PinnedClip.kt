package fr.junade.taipo.clipboard

/** Story 2.5 : élément du presse-papiers épinglé par l'utilisateur (texte en clair en mémoire, chiffré sur disque). */
data class PinnedClip(val id: Long, val text: String, val pinnedAtMillis: Long)
