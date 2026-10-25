package fr.junade.taipo

/** Les deux états de la barre du haut : « normale » (champ vide) et « saisie » (texte présent ou auto-remplissage proposé). */
enum class BarMode { NORMAL, TYPING }

/**
 * État de la barre du haut, en deux états.
 *
 * - [BarMode.NORMAL] (champ vide) : Paramètres, Smart Clipboard (avec la puce de collage à côté quand une copie récente est
 *   proposée), Annuler, les actions IA (Chat, Corriger grisé, Transcription IA) et Vocal fixé à droite.
 * - [BarMode.TYPING] (du texte est présent, ou le service d'auto-remplissage propose des suggestions en ligne) : le robot est
 *   ancré à gauche, suivi de la bande de mots (ou des suggestions d'auto-remplissage), puis Corriger (quand aucune suggestion
 *   n'est affichée) et Vocal à droite.
 * - Menu de gauche (le robot) ouvert, pendant la saisie seulement : la bande disparaît, la rangée devient Robot (actif),
 *   Paramètres, Smart Clipboard, Annuler, Chat, Corriger, Transcription et Vocal, à espacement égal.
 * - Panneau Smart Clipboard ouvert : la barre du haut ne change pas. Son état d'affichage est figé à l'ouverture et repris
 *   tel quel jusqu'à la fermeture, même si le texte du champ change entre-temps.
 * - Une écoute (Vocal, Transcription IA) lancée depuis la barre normale la garde affichée jusqu'à sa fin ([holdNormalWhile]) :
 *   le texte que la dictée insère au fil de l'eau ne fait donc pas basculer la barre en saisie, et les boutons qui servent
 *   d'arrêt restent à leur place.
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

    /** Une écoute lancée depuis la barre normale est en cours : la barre normale reste affichée. */
    private var heldByAction: Boolean = false

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
        refreezeDisplay()
    }

    /**
     * Une écoute ([active]) est en cours : si elle a démarré depuis la barre normale, celle-ci reste affichée jusqu'à la fin
     * de l'écoute, même si du texte arrive dans le champ entre-temps (dictée au fil de l'eau). Démarrée pendant la saisie,
     * l'écoute ne fige rien : la barre de saisie reste de toute façon affichée tant que le champ contient du texte.
     * À la fin, la barre suit de nouveau l'état réel (donc passe en saisie si le texte dicté est dans le champ).
     */
    fun holdNormalWhile(active: Boolean) {
        if (active == heldByAction) return
        if (active && mode != BarMode.NORMAL) return
        heldByAction = active
        refreezeDisplay()
    }

    /** Recalcule l'affichage figé : par le panneau Smart Clipboard (état au moment de l'ouverture) ou par une écoute (barre normale). */
    private fun refreezeDisplay() {
        frozen = when {
            clipboardPanelOpen -> frozen ?: Shown(liveMode, liveMenuOpen && liveMode == BarMode.TYPING)
            heldByAction -> Shown(BarMode.NORMAL, menuOpen = false)
            else -> null
        }
    }

    private fun closeMenuIfUnavailable() {
        if (liveMode != BarMode.TYPING) liveMenuOpen = false
    }
}
