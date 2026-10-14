package fr.junade.taipo

import android.content.Context
import android.view.inputmethod.InputConnection
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents

/**
 * Bouton Annuler de la barre : tient l'[UndoHistory] des dix dernières actions du clavier et les défait une à une.
 * L'historique est propre au champ en cours (vidé à chaque nouveau champ). Tout se passe sur le thread principal.
 */
class UndoController(
    private val context: Context,
    private val host: Host,
) {

    interface Host {
        fun inputConnection(): InputConnection?

        fun showMessage(message: String)

        /** Juste avant d'annuler : le surlignage de correction est retiré, il ne vaut plus une fois le texte modifié. */
        fun beforeUndo()

        /** Juste après : l'IME resynchronise majuscule automatique, clavier et barre. */
        fun afterUndo()

        /** Le bouton Annuler s'active (une action est annulable) ou se grise (l'historique est vide). */
        fun onUndoAvailabilityChanged(canUndo: Boolean)
    }

    private val history = UndoHistory()

    val canUndo: Boolean get() = !history.isEmpty

    /** Enregistre une action dont le geste d'annulation est [undo]. */
    fun record(undo: UndoHistory.Undo) {
        history.push(undo)
        host.onUndoAvailabilityChanged(true)
    }

    /**
     * Enregistre une insertion dans le champ : [inserted] est ce que le clavier a écrit, à la place de [original]
     * (vide pour une insertion pure). Sans effet si rien ne change.
     */
    fun recordEdit(inserted: String, original: String) {
        if (inserted.isEmpty() || inserted == original) return
        record { host.inputConnection()?.let { FieldUndo.replace(it, inserted, original) } ?: false }
    }

    /**
     * Enregistre une suppression faite avec la touche supprimer : [deleted] a été effacé juste après [anchor].
     * [before] : le texte qui précédait le curseur juste avant l'effacement ; il permet de regrouper des appuis
     * successifs en une seule action ([mergeable], vrai pour un effacement caractère par caractère). [offsetProvider]
     * donne la position de la zone effacée ; il n'est appelé que pour une nouvelle action (pas pour un regroupement).
     */
    fun recordDeletion(
        deleted: String,
        anchor: String,
        before: String,
        mergeable: Boolean,
        offsetProvider: () -> Int,
    ) {
        if (deleted.isEmpty()) return
        val top = history.last()
        if (mergeable && top is DeletionUndo && top.tryMerge(deleted, before)) {
            host.onUndoAvailabilityChanged(true)
            return
        }
        record(
            DeletionUndo(deleted, offsetProvider(), anchor) { text, offset, expectedAnchor ->
                host.inputConnection()?.let { FieldUndo.reinsert(it, text, offset, expectedAnchor) } ?: false
            },
        )
    }

    /** Oublie l'historique (autre champ, clavier fermé). [notify] : grise le bouton ; faux quand l'appelant s'en charge. */
    fun clear(notify: Boolean = true) {
        if (history.isEmpty) return
        history.clear()
        if (notify) host.onUndoAvailabilityChanged(false)
    }

    /** Touche sur le bouton Annuler : défait la dernière action enregistrée. */
    fun onUndoClicked() {
        if (history.isEmpty) return
        Analytics.track(context, AnalyticsEvents.UNDO_USED)
        host.beforeUndo()
        if (history.undoLast() == UndoHistory.Outcome.FAILED) {
            host.showMessage(context.getString(R.string.undo_failed))
        }
        host.afterUndo()
        host.onUndoAvailabilityChanged(canUndo)
    }
}
