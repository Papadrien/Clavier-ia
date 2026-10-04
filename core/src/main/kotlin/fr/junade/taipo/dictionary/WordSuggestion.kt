package fr.junade.taipo.dictionary

/**
 * Story 1.17 : un emplacement de mot de la bande de suggestions.
 *
 * - [Kind.AUTOCORRECTION] : le mot qui remplacera le mot tapé quand l'utilisateur appuie sur espace
 *   (même correction que l'autocorrection de la story 1.3). Affiché au centre et en gras.
 * - [Kind.TYPED] : le mot tel que tapé, proposé à côté d'une autocorrection pour la refuser.
 * - [Kind.COMPLETION] : simple suggestion (complétion du mot en cours), en poids normal.
 * - [Kind.PREDICTION] : mot qui pourrait suivre, d'après les habitudes d'écriture de l'utilisateur,
 *   proposé après une espace (aucun mot n'est en cours de frappe) ; il s'ajoute au texte, sans rien remplacer.
 */
data class WordSuggestion(val text: String, val kind: Kind) {
    enum class Kind { TYPED, AUTOCORRECTION, COMPLETION, PREDICTION }

    /** Vrai si ce mot va remplacer le mot tapé à la prochaine espace (affichage en gras). */
    val replacesOnSpace: Boolean get() = kind == Kind.AUTOCORRECTION
}
