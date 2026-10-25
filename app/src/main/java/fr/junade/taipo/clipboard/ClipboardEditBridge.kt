package fr.junade.taipo.clipboard

/**
 * Story 2.6 : passerelle entre le clavier ([fr.junade.taipo.TaipoIme]) et l'écran de modification
 * ([fr.junade.taipo.ClipboardEditActivity]) pour la dernière copie, qui n'existe qu'en mémoire du
 * clavier (un élément épinglé, lui, est relu et réécrit dans la base par son identifiant).
 *
 * Les deux tournent dans le même processus et sur le thread principal.
 */
object ClipboardEditBridge {

    /** Texte de la dernière copie à modifier : posé par le clavier, lu une fois (puis effacé) par l'écran. */
    @Volatile
    var lastClipText: String? = null

    /** Appelé par l'écran avec le texte modifié de la dernière copie ; défini par le clavier tant qu'il vit. */
    @Volatile
    var onLastClipEdited: ((String) -> Unit)? = null
}
