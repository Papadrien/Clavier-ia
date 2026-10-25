package fr.junade.taipo.clipboard

/**
 * Story 2.5 : élément du presse-papiers épinglé par l'utilisateur (texte en clair en mémoire,
 * chiffré sur disque). [label] : étiquette (story 2.7), null s'il n'en a pas.
 */
data class PinnedClip(val id: Long, val text: String, val pinnedAtMillis: Long, val label: String? = null)
