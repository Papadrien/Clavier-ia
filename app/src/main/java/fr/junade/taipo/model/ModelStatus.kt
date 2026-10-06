package fr.junade.taipo.model

/**
 * Lot UX 4 : statut d'un modèle affiché sur la ligne correspondante de l'accueil. Logique pure (sans vue), testée en
 * JVM ; la lecture des préférences est dans `MainActivity`.
 *
 * Pas de téléchargement dans cette version (l'utilisateur fournit les fichiers lui-même) : les statuts décrivent donc
 * « fichier(s) fourni(s) ou non », pas un état de téléchargement.
 */
enum class ModelStatus {
    /** Tous les fichiers nécessaires sont associés au modèle. */
    READY,

    /** Modèle vocal : une partie seulement des fichiers est associée. */
    INCOMPLETE,

    /** Aucun fichier associé. */
    MISSING,
    ;

    companion object {
        /** Modèle IA : prêt dès qu'un fichier est associé au modèle actif ([fileProvided] faux s'il n'y a pas de modèle actif). */
        fun forTextModel(fileProvided: Boolean): ModelStatus = if (fileProvided) READY else MISSING

        /** Modèle vocal : [provided] fichiers associés sur [total] attendus. */
        fun forVoiceModel(provided: Int, total: Int): ModelStatus = when {
            total <= 0 || provided <= 0 -> MISSING
            provided >= total -> READY
            else -> INCOMPLETE
        }
    }
}
