package fr.junade.taipo

import android.os.SystemClock

/**
 * Dernière autocorrection du dictionnaire, tant que rien d'autre n'a été fait depuis :
 * une suppression la rétablit (mot tel que tapé), comme sur les claviers usuels, mais seulement dans les
 * [AutocorrectionMemory.UNDO_WINDOW_MS] qui suivent la correction et tant que le curseur est resté derrière elle.
 */
internal class AppliedAutocorrection(
    val original: String,
    val corrected: String,
    val boundary: String = "",
    // Texte avant le curseur au moment de la correction (pour les habitudes) ; vide si non renseigné.
    val textBefore: String = "",
    // Instant de la correction (horloge monotone), pour borner le délai pendant lequel Supprimer l'annule.
    val appliedAtMillis: Long = SystemClock.elapsedRealtime(),
) {
    /** Vrai si le délai d'annulation par Supprimer est dépassé. */
    fun isUndoWindowExpired(): Boolean = SystemClock.elapsedRealtime() - appliedAtMillis > AutocorrectionMemory.UNDO_WINDOW_MS
}

/**
 * Mémoire de l'autocorrection (sortie de [TaipoIme], revue de code), partagée par la frappe dans le champ et dans le prompt :
 * la dernière correction appliquée ([pending], annulable par Supprimer) et le mot que l'utilisateur vient de rétablir, qui ne
 * doit pas être revu à rebours quand le mot suivant est tapé ([allowContextualRetro]).
 */
internal class AutocorrectionMemory {

    var pending: AppliedAutocorrection? = null

    /**
     * Texte qui entourait le curseur quand l'utilisateur a annulé une autocorrection (retour arrière juste après) :
     * le mot qu'il vient de rétablir n'est pas recorrigé à rebours quand le mot suivant est tapé.
     */
    private var revertedTail: String? = null

    /** L'utilisateur vient de rétablir [restored] (mot tapé et son séparateur). */
    fun onReverted(restored: String) {
        revertedTail = restored.takeLast(REVERTED_TAIL_LENGTH)
    }

    /** Faux si le mot précédent vient d'être rétabli par l'utilisateur : il ne doit pas être revu à rebours. */
    fun allowContextualRetro(textBeforeCursor: String): Boolean {
        val tail = revertedTail ?: return true
        revertedTail = null
        return !textBeforeCursor.contains(tail)
    }

    companion object {
        /** Délai après une autocorrection pendant lequel la touche Supprimer peut encore l'annuler. */
        const val UNDO_WINDOW_MS = 5_000L

        /** Longueur du texte retenu quand une autocorrection est annulée. */
        private const val REVERTED_TAIL_LENGTH = 12
    }
}

/** Règles de frappe communes au champ et au prompt (sorties de [TaipoIme]). Logique pure. */
internal object TypingRules {

    /** Une touche qui termine un mot (espace, Entrée, ponctuation) : le mot qui précède est vérifié par l'autocorrection. */
    fun isWordBoundaryKey(key: Key): Boolean = when (val action = key.action) {
        KeyAction.Space, KeyAction.Enter -> true
        is KeyAction.TypeChar -> !action.char.isLetterOrDigit() && action.char != '\'' && action.char != '-'
        else -> false
    }

    /**
     * Séparateur ajouté après un mot suggéré touché : une espace, sauf si une virgule suit déjà le mot
     * ([textAfter] = texte après le curseur, [wordAfter] = suite du mot), pour ne pas obtenir « mot , ».
     */
    fun separatorAfterSuggestion(textAfter: String, wordAfter: String): String =
        if (textAfter.getOrNull(wordAfter.length) == ',') "" else " "
}
