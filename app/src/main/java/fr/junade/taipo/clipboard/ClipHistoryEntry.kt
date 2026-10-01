package fr.junade.taipo.clipboard

/** Story 2.9 : une copie de l'historique (texte en clair en mémoire, chiffré sur disque). */
data class ClipHistoryEntry(val id: Long, val text: String, val copiedAtMillis: Long, val sensitive: Boolean)
