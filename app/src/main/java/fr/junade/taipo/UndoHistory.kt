package fr.junade.taipo

/**
 * Historique des dernières actions annulables du clavier (bouton Annuler de la barre) : collage, suggestion ou
 * autocorrection acceptée, emoji, correction IA, dictée, transcription IA. Chaque action enregistre le geste qui la
 * défait ([Undo]) ; le bouton annule la plus récente, puis la précédente, jusqu'à [capacity] actions.
 *
 * Logique pure (sans Android), testée en JVM.
 */
class UndoHistory(private val capacity: Int = MAX_ACTIONS) {

    /** Défait une action. Renvoie faux si ce n'est plus possible (le texte a changé entre-temps). */
    fun interface Undo {
        fun run(): Boolean
    }

    enum class Outcome { EMPTY, DONE, FAILED }

    init {
        require(capacity > 0) { "capacity doit être positif" }
    }

    private val entries = ArrayDeque<Undo>()

    val size: Int get() = entries.size

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Ajoute une action ; au-delà de [capacity], la plus ancienne est oubliée. */
    fun push(undo: Undo) {
        entries.addLast(undo)
        while (entries.size > capacity) entries.removeFirst()
    }

    fun clear() {
        entries.clear()
    }

    /**
     * Annule l'action la plus récente. Elle sort de l'historique même si elle échoue : l'appui suivant vise alors la
     * précédente, au lieu de buter indéfiniment sur la même.
     */
    fun undoLast(): Outcome {
        val last = entries.removeLastOrNull() ?: return Outcome.EMPTY
        return if (last.run()) Outcome.DONE else Outcome.FAILED
    }

    companion object {
        /** Nombre d'actions que le bouton Annuler peut remonter. */
        const val MAX_ACTIONS = 10
    }
}
