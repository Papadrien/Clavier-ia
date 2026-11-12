package fr.junade.taipo

import android.view.inputmethod.EditorInfo

/**
 * Audit BUG-01 : ce que fait la touche Entrée dans le champ courant, d'après ses `imeOptions`.
 *
 * - [Newline] : champ multiligne (`IME_FLAG_NO_ENTER_ACTION`) ou sans action : retour à la ligne.
 * - [Perform] : action de l'éditeur (Envoyer, Rechercher, Suivant, OK…), exécutée par `performEditorAction`, comme le
 *   clavier d'Android. Un `KEYCODE_ENTER` simulé n'est pas compris par toutes les applications (WebView, Flutter,
 *   React Native, champs qui testent `actionId`). Une action personnalisée (`actionLabel` + `actionId`) passe avant
 *   l'action standard.
 *
 * Logique pure (constantes seulement), testée sur la JVM (`EnterActionTest`).
 */
sealed interface EnterAction {
    data object Newline : EnterAction

    data class Perform(val actionId: Int) : EnterAction

    /** Icône de la touche Entrée pour cette action. */
    enum class Icon { ENTER, SEARCH, SEND, NEXT, DONE }

    companion object {
        fun of(imeOptions: Int, customActionId: Int = 0, hasCustomLabel: Boolean = false): EnterAction {
            if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Newline
            if (hasCustomLabel && customActionId != EditorInfo.IME_ACTION_UNSPECIFIED) return Perform(customActionId)
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) return Newline
            return Perform(action)
        }

        fun of(info: EditorInfo?): EnterAction =
            if (info == null) Newline else of(info.imeOptions, info.actionId, info.actionLabel != null)

        /** Icône selon l'action standard ; une action personnalisée ou inconnue garde la flèche d'entrée. */
        fun iconFor(action: EnterAction): Icon = when (action) {
            Newline -> Icon.ENTER
            is Perform -> when (action.actionId) {
                EditorInfo.IME_ACTION_SEARCH -> Icon.SEARCH
                EditorInfo.IME_ACTION_SEND -> Icon.SEND
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Icon.NEXT
                EditorInfo.IME_ACTION_DONE -> Icon.DONE
                else -> Icon.ENTER
            }
        }
    }
}
