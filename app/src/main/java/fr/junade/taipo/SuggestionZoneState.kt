package fr.junade.taipo

/**
 * Stories 2.1 et 2.2 : que montre la zone de gauche de la barre du haut : les suggestions de mots
 * (pendant la saisie), le bouton Smart Clipboard (barre « avant saisie ») ou la puce de collage
 * (une copie récente est proposée) ?
 *
 * - Champ vide (« avant saisie ») : le bouton Smart Clipboard est visible dans la barre.
 * - Dès qu'il y a du texte : le bouton est caché et la zone montre les suggestions de mots. Il reste
 *   accessible par le bouton « menu » (à gauche de la barre, visible seulement quand il y a du
 *   texte), qui bascule la zone vers l'affichage « avant saisie ».
 * - Story 2.2 : tant qu'un collage est proposé, la puce prend la place de la bande de mots (et du
 *   bouton Smart Clipboard). Le bouton « menu » est alors visible même sur un champ vide : il
 *   donne accès au bouton Smart Clipboard à la place de la puce, et la puce revient en le retouchant.
 * - Retour automatique aux suggestions de mots dès le début de la frappe (story 2.4, déjà gérée ici
 *   par [onTyping]) ; le menu ne reste jamais ouvert quand le bouton « menu » n'est pas visible.
 *
 * - Deux menus « ··· » : celui de gauche (ci-dessus) donne accès au bouton Smart Clipboard ; celui de
 *   droite n'existe que pendant les suggestions de mots et range derrière lui les boutons Vocal et
 *   Corriger ([actionButtonsVisible], [toggleActionsMenu]). Un seul est ouvert à la fois ; la frappe
 *   referme les deux.
 *
 * Logique pure (sans Android), testée en JVM.
 */
class SuggestionZoneState {

    enum class Zone { WORDS, CLIPBOARD, PASTE, ACTIONS }

    var fieldHasText: Boolean = false
        private set

    var menuExpanded: Boolean = false
        private set

    /** Story 2.2 : un collage est proposé (copie récente, non collée, non écartée). */
    var pasteAvailable: Boolean = false
        private set

    /**
     * Menu de droite : les boutons Vocal et Corriger, rangés derrière lui tant que la bande de mots
     * est affichée, sont ouverts (la bande de mots s'efface alors, la zone reste vide).
     */
    var actionsExpanded: Boolean = false
        private set

    val zone: Zone
        get() = when {
            menuExpanded -> Zone.CLIPBOARD
            pasteAvailable -> Zone.PASTE
            !fieldHasText -> Zone.CLIPBOARD
            actionsExpanded -> Zone.ACTIONS
            else -> Zone.WORDS
        }

    /**
     * Le bouton « menu » existe pendant la saisie (le champ contient du texte) et tant qu'un collage
     * est proposé (la puce a pris la place du bouton Smart Clipboard).
     */
    val menuButtonVisible: Boolean
        get() = fieldHasText || pasteAvailable

    /**
     * Les boutons Vocal et Corriger sont rangés derrière le menu de droite tant que la bande de mots
     * est affichée. Ils restent visibles dans les autres zones (bouton Smart Clipboard, puce de
     * collage, menu de droite ouvert) et quand [busy] : écoute, transcription ou correction en cours,
     * où le bouton sert d'arrêt ou d'indicateur d'avancement.
     */
    fun actionButtonsVisible(busy: Boolean): Boolean = busy || zone != Zone.WORDS

    /**
     * Le menu de droite n'existe que pendant les suggestions de mots (fermé ou ouvert) : avec le
     * bouton Smart Clipboard ou la puce, Vocal et Corriger sont déjà visibles, et pendant une
     * écoute ou une correction ils sont affichés d'office.
     */
    fun actionsMenuButtonVisible(busy: Boolean): Boolean =
        !busy && (zone == Zone.WORDS || zone == Zone.ACTIONS)

    /** Touche sur le menu de droite : sans effet quand il n'est pas visible. */
    fun toggleActionsMenu() {
        if (zone == Zone.WORDS) {
            actionsExpanded = true
        } else if (zone == Zone.ACTIONS) {
            actionsExpanded = false
        }
    }


    /** Le champ contient (ou non) du texte : un champ vidé referme le menu, sauf si la puce le garde visible. */
    fun onFieldTextChanged(hasText: Boolean) {
        fieldHasText = hasText
        closeMenuIfUnavailable()
    }

    /** Story 2.2 : un collage est (ou n'est plus) proposé. */
    fun setPasteAvailable(available: Boolean) {
        pasteAvailable = available
        closeMenuIfUnavailable()
    }

    /** Touche sur le bouton « menu » : sans effet quand il n'est pas visible. */
    fun toggleMenu() {
        if (!menuButtonVisible) return
        menuExpanded = !menuExpanded
        if (menuExpanded) actionsExpanded = false // un seul menu ouvert à la fois
    }

    /** Début (ou poursuite) de la frappe : retour aux suggestions de mots. */
    fun onTyping() {
        menuExpanded = false
        actionsExpanded = false
    }

    private fun closeMenuIfUnavailable() {
        if (!menuButtonVisible) menuExpanded = false
        // Le menu de droite n'existe que pendant les suggestions de mots (texte présent, pas de puce).
        if (!fieldHasText || pasteAvailable) actionsExpanded = false
    }
}
