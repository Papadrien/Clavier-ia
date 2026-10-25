package fr.junade.taipo.model

/**
 * Story 8.5 : le modèle « actif » que les actions IA du clavier (Corriger, Générer) peuvent réellement utiliser.
 *
 * Un modèle choisi dont le fichier a disparu (restauration sur un autre appareil, suppression) n'est pas utilisable :
 * on le traite comme « aucun modèle », ce qui déclenche la redirection vers l'écran « Modèle IA ».
 */
object ModelAvailability {
    fun usable(active: AiModel?, isInstalled: (AiModel) -> Boolean): AiModel? =
        active?.takeIf(isInstalled)
}
