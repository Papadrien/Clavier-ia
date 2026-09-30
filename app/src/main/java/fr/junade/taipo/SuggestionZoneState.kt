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
 * Logique pure (sans Android), testée en JVM.
 */
class SuggestionZoneState {

    enum class Zone { WORDS, CLIPBOARD, PASTE }

    var fieldHasText: Boolean = false
        private set

    var menuExpanded: Boolean = false
        private set

    /** Story 2.2 : un collage est proposé (copie récente, non collée, non écartée). */
    var pasteAvailable: Boolean = false
        private set

    val zone: Zone
        get() = when {
            menuExpanded -> Zone.CLIPBOARD
            pasteAvailable -> Zone.PASTE
            !fieldHasText -> Zone.CLIPBOARD
            else -> Zone.WORDS
        }

    /**
     * Le bouton « menu » existe pendant la saisie (le champ contient du texte) et tant qu'un collage
     * est proposé (la puce a pris la place du bouton Smart Clipboard).
     */
    val menuButtonVisible: Boolean
        get() = fieldHasText || pasteAvailable

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
        if (menuButtonVisible) menuExpanded = !menuExpanded
    }

    /** Début (ou poursuite) de la frappe : retour aux suggestions de mots. */
    fun onTyping() {
        menuExpanded = false
    }

    private fun closeMenuIfUnavailable() {
        if (!menuButtonVisible) menuExpanded = false
    }
}
