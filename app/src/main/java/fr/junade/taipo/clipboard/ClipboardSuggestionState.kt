package fr.junade.taipo.clipboard

/**
 * Stories 2.2 et 2.3 : la puce « coller » de la barre est-elle proposée, et pour quel texte ?
 *
 * Seule la dernière copie est gardée ici, en mémoire vive ; l'historique persistant (story 2.9) est
 * géré par [ClipHistoryRepository]. La puce n'est proposée que si la copie :
 * - n'est pas vide (ni faite uniquement d'espaces) ;
 * - n'est pas trop grosse ([maxChars] : au-delà, `commitText` peut échouer) ;
 * - n'a pas déjà été collée ;
 * - n'a pas été écartée par une frappe ([onTyping]) ;
 * - n'a pas expiré, [expirationMillis] après la copie (story 2.3, 10 minutes par défaut).
 *
 * Une copie se reconnaît à son horodatage et à son contenu : relire le presse-papiers (ouverture
 * d'un champ, redémarrage du processus du clavier) ne ressuscite donc pas une puce déjà écartée
 * ou collée, alors qu'une nouvelle copie, même du même texte (autre horodatage), la fait revenir.
 *
 * Story 2.10 : la copie est sensible si l'application source l'a signalée ou si son texte (modifié
 * compris) ressemble à un numéro de carte ([SensitiveContentDetector]) ; son aperçu est alors masqué.
 *
 * L'horloge est injectable pour les tests. Logique pure (sans Android), testée en JVM.
 */
class ClipboardSuggestionState(
    private val clock: () -> Long,
    private val maxChars: Int = MAX_CHARS,
    private val expirationMillis: Long = EXPIRATION_MILLIS,
    private val panelRetentionMillis: Long = ClipboardItems.HISTORY_RETENTION_MILLIS,
) {

    /** Ce que la puce propose : le texte à coller, et s'il est à masquer à l'écran. */
    data class Suggestion(val text: String, val sensitive: Boolean)

    private class Clip(
        val text: String,
        val copiedAtMillis: Long,
        /** Faux si le système n'a pas fourni d'horodatage : [copiedAtMillis] est alors l'heure de première lecture. */
        val timestampKnown: Boolean,
        val sensitive: Boolean,
        /** Story 2.6 : texte modifié par l'utilisateur dans le panneau ; [text] reste la copie telle que le système la fournit. */
        val edited: String? = null,
    ) {
        /** Le texte que le panneau et la puce montrent. */
        val shown: String get() = edited ?: text
    }

    private var clip: Clip? = null
    private var dismissed = false
    private var pasted = false
    private var deleted = false

    /**
     * Le presse-papiers vient d'être lu : [text] (null = vide ou illisible, ou contenu qui n'est
     * pas du texte), copié à [copiedAtMillis] (0 ou moins = inconnu), [sensitive] si l'application
     * source l'a signalé comme sensible.
     *
     * Retourne vrai si c'est une nouvelle copie (la dernière copie en mémoire vient d'être
     * remplacée) : l'appelant l'enregistre alors dans l'historique (story 2.9). Faux pour une
     * relecture de la même copie, et quand [text] est null.
     */
    fun onClipRead(text: String?, copiedAtMillis: Long, sensitive: Boolean): Boolean {
        if (text == null) {
            clear()
            return false
        }
        val current = clip
        val sameCopy = current != null && current.text == text &&
            (copiedAtMillis <= 0 || !current.timestampKnown || copiedAtMillis == current.copiedAtMillis)
        if (sameCopy && current != null) {
            // Relecture de la même copie : état conservé (écartée/collée le reste), sensible le reste aussi.
            if (sensitive && !current.sensitive) {
                clip = Clip(current.text, current.copiedAtMillis, current.timestampKnown, sensitive = true, edited = current.edited)
            }
            return false
        }
        val known = copiedAtMillis > 0
        clip = Clip(text, if (known) copiedAtMillis else clock(), known, sensitive)
        dismissed = false
        pasted = false
        deleted = false
        return true
    }

    /**
     * Story 2.5 : la dernière copie telle que le panneau Presse-papiers la montre, même si la puce
     * a été écartée, collée ou a expiré ; null si elle est vide, trop grosse, supprimée du panneau
     * ou plus ancienne que la rétention du panneau (1 h après la copie, story 2.9).
     */
    fun lastClip(): Suggestion? {
        val current = clip ?: return null
        if (deleted || current.shown.isBlank() || current.shown.length > maxChars) return null
        if (clock() - current.copiedAtMillis >= panelRetentionMillis) return null
        return Suggestion(current.shown, ClipboardItems.isSensitive(current.shown, current.sensitive))
    }

    /** La suggestion à afficher maintenant, ou null si aucune puce ne doit être proposée. */
    fun suggestion(): Suggestion? {
        val current = clip ?: return null
        if (dismissed || pasted || deleted) return null
        if (current.shown.isBlank() || current.shown.length > maxChars) return null
        if (remainingMillis(current) <= 0) return null
        return Suggestion(current.shown, ClipboardItems.isSensitive(current.shown, current.sensitive))
    }

    /** Durée avant l'expiration de la suggestion affichée, ou null s'il n'y en a pas (rien à planifier). */
    fun expiresInMillis(): Long? {
        val current = clip ?: return null
        if (suggestion() == null) return null
        return remainingMillis(current)
    }

    /** La copie vient d'être collée : plus de puce pour cette copie. */
    fun onPasted() {
        if (clip != null) pasted = true
    }

    /** Story 2.5 : la copie est supprimée depuis le panneau (une nouvelle copie la remplace, elle ne revient pas seule). */
    fun onDeleted() {
        if (clip != null) deleted = true
    }

    /**
     * Story 2.6 : la dernière copie est modifiée depuis le panneau ; la puce et le panneau montrent
     * [newText]. La copie garde son horodatage et son état (collée, écartée, expirée) : relire le
     * presse-papiers Android, qui contient toujours le texte d'origine, ne défait pas la
     * modification, et une nouvelle copie la remplace. Texte vide ignoré ; le presse-papiers
     * Android lui-même n'est pas modifié.
     */
    fun onEdited(newText: String) {
        val current = clip ?: return
        if (newText.isBlank()) return
        clip = Clip(current.text, current.copiedAtMillis, current.timestampKnown, current.sensitive, edited = newText)
    }

    /** Première frappe : la puce est écartée pour cette copie (une nouvelle copie la fait revenir). */
    fun onTyping() {
        if (clip != null) dismissed = true
    }

    fun clear() {
        clip = null
        dismissed = false
        pasted = false
        deleted = false
    }

    private fun remainingMillis(current: Clip): Long = current.copiedAtMillis + expirationMillis - clock()

    companion object {
        /** Au-delà, pas de suggestion : `commitText` peut échouer sur de très gros textes (limite Binder). */
        const val MAX_CHARS = 100_000

        /** Story 2.3 : la suggestion expire 10 minutes après la copie. */
        const val EXPIRATION_MILLIS = 10 * 60 * 1000L
    }
}
