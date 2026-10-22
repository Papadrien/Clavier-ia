package fr.junade.taipo

/** Les deux états de la barre du haut : « normale » (champ vide) et « saisie » (texte présent ou auto-remplissage proposé). */
enum class BarMode { NORMAL, TYPING }

/**
 * État de la barre du haut, en deux états.
 *
 * - [BarMode.NORMAL] (champ vide) : Paramètres, Smart Clipboard (avec la puce de collage à côté quand une copie récente est
 *   proposée), Annuler, et Vocal fixé à droite.
 * - [BarMode.TYPING] (du texte est présent, ou le service d'auto-remplissage propose des suggestions en ligne) : le robot est
 *   ancré à gauche, suivi de la bande de mots (ou des suggestions d'auto-remplissage), puis Corriger (quand aucune suggestion
 *   n'est affichée) et Vocal à droite.
 * - Menu de gauche (le robot) ouvert, pendant la saisie seulement : la bande disparaît, la rangée devient Robot (actif),
 *   Paramètres, Smart Clipboard, Annuler, Chat, Corriger, Transcription et Vocal, à espacement égal.
 * - Panneau Smart Clipboard ouvert : la barre du haut ne change pas. Son état d'affichage est figé à l'ouverture et repris
 *   tel quel jusqu'à la fermeture, même si le texte du champ change entre-temps.
 * - Le début de la frappe referme le menu ([onTyping]) ; un champ vidé (sans auto-remplissage) ramène à la barre normale.
 *
 * Logique pure (sans Android), testée en JVM.
 */
class SuggestionZoneState {

    private data class Shown(val mode: BarMode, val menuOpen: Boolean)

    var fieldHasText: Boolean = false
        private set

    /** Story 2.2 : un collage est proposé (copie récente, non collée, non écartée). */
    var pasteAvailable: Boolean = false
        private set

    /** Le service d'auto-remplissage propose des suggestions en ligne (identifiants, mots de passe). */
    var inlineAvailable: Boolean = false
        private set

    /** Le panneau Smart Clipboard est ouvert : l'affichage de la barre est figé. */
    var clipboardPanelOpen: Boolean = false
        private set

    private var liveMenuOpen: Boolean = false
    private var frozen: Shown? = null

    private val liveMode: BarMode
        get() = if (fieldHasText || inlineAvailable) BarMode.TYPING else BarMode.NORMAL

    /** État de barre affiché (celui de l'ouverture du panneau Smart Clipboard tant qu'il est ouvert). */
    val mode: BarMode
        get() = frozen?.mode ?: liveMode

    /** Menu de gauche (robot) ouvert ; jamais vrai dans la barre normale. */
    val menuOpen: Boolean
        get() = mode == BarMode.TYPING && (frozen?.menuOpen ?: liveMenuOpen)

    /** Le robot existe pendant la saisie seulement. */
    val menuButtonVisible: Boolean
        get() = mode == BarMode.TYPING

    /** Touche sur le robot : sans effet dans la barre normale. */
    fun toggleMenu() {
        if (liveMode != BarMode.TYPING) return
        liveMenuOpen = !liveMenuOpen
    }

    /** Début (ou poursuite) de la frappe : le menu se referme, retour aux suggestions de mots. */
    fun onTyping() {
        liveMenuOpen = false
    }

    /** Le champ contient (ou non) du texte. */
    fun onFieldTextChanged(hasText: Boolean) {
        fieldHasText = hasText
        closeMenuIfUnavailable()
    }

    /** Des suggestions d'auto-remplissage en ligne sont (ou ne sont plus) disponibles. */
    fun setInlineAvailable(available: Boolean) {
        inlineAvailable = available
        closeMenuIfUnavailable()
    }

    /** Story 2.2 : un collage est (ou n'est plus) proposé. */
    fun setPasteAvailable(available: Boolean) {
        pasteAvailable = available
    }

    /** Le panneau Smart Clipboard s'ouvre (l'affichage actuel est figé) ou se ferme (l'affichage suit de nouveau l'état réel). */
    fun setClipboardPanelOpen(open: Boolean) {
        if (clipboardPanelOpen == open) return
        clipboardPanelOpen = open
        frozen = if (open) Shown(liveMode, liveMenuOpen && liveMode == BarMode.TYPING) else null
    }

    private fun closeMenuIfUnavailable() {
        if (liveMode != BarMode.TYPING) liveMenuOpen = false
    }
}
