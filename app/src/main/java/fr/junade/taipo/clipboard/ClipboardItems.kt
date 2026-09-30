package fr.junade.taipo.clipboard

/**
 * Story 2.5 : cartes du panneau Presse-papiers et règles d'épinglage.
 *
 * Le panneau montre la dernière copie (mémoire vive, sans expiration avant l'historique de la
 * story 2.9) puis les éléments épinglés, du plus récent au plus ancien. Si la dernière copie est
 * déjà épinglée, seule la carte épinglée est montrée.
 *
 * Logique pure (sans Android), testée en JVM.
 */
object ClipboardItems {

    /** Taille maximale d'un élément épinglé (caractères). */
    const val MAX_PINNED_CHARS = 10_000

    /** Nombre maximal d'éléments épinglés. */
    const val MAX_PINNED = 50

    enum class PinResult { PINNED, ALREADY_PINNED, FULL, TOO_LONG, EMPTY, SENSITIVE }

    /**
     * Une carte du panneau : [pinnedId] est l'identifiant en base pour un élément épinglé (null
     * sinon) ; [isLastClip] vaut vrai pour la dernière copie, épinglée ou non.
     */
    data class Item(val text: String, val sensitive: Boolean, val pinnedId: Long?, val isLastClip: Boolean) {
        val pinned: Boolean get() = pinnedId != null
    }

    fun build(lastClip: ClipboardSuggestionState.Suggestion?, pinned: List<PinnedClip>): List<Item> {
        val items = ArrayList<Item>(pinned.size + 1)
        if (lastClip != null && pinned.none { it.text == lastClip.text }) {
            items += Item(lastClip.text, lastClip.sensitive, pinnedId = null, isLastClip = true)
        }
        pinned.forEach { items += Item(it.text, sensitive = false, pinnedId = it.id, isLastClip = it.text == lastClip?.text) }
        return items
    }

    /** Raison pour laquelle [text] ne peut pas être épinglé, ou null s'il peut l'être (doublon et plafond : voir la base). */
    fun pinRefusal(text: String, sensitive: Boolean): PinResult? = when {
        sensitive -> PinResult.SENSITIVE
        text.isBlank() -> PinResult.EMPTY
        text.length > MAX_PINNED_CHARS -> PinResult.TOO_LONG
        else -> null
    }
}
