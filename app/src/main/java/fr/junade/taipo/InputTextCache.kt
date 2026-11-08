package fr.junade.taipo

/**
 * Cache des lectures du champ de l'application (revue de code, performance). Chaque lecture du texte autour du curseur
 * (`getTextBeforeCursor`, `getTextAfterCursor`, `getSelectedText`) est un aller-retour synchrone vers l'application qui
 * possède le champ : une touche en faisait environ huit (majuscule automatique, suggestions, barre du haut, puis les mêmes à
 * la notification de déplacement du curseur qui suit). Ce cache, utilisé par [CachingInputConnection], les ramène à une
 * lecture réelle tous les [ttlMs] au plus pendant une frappe continue.
 *
 * Principe (celui du clavier Android d'origine, AOSP) :
 * - une lecture remplit le cache (au moins [MIN_WINDOW] caractères, pour servir aussi les petites lectures qui suivent) ;
 * - nos propres écritures le mettent à jour sur place quand la position du curseur est connue : `commitText` ajoute le texte
 *   avant le curseur, `deleteSurroundingText` en retire ; le texte après le curseur ne bouge pas. Toute autre écriture
 *   (touche envoyée, sélection déplacée, action de l'éditeur…) le vide ;
 * - la notification de déplacement du curseur ([onSelectionUpdate]) qui correspond à nos écritures (position attendue) le
 *   garde ; toute autre (toucher de l'utilisateur, texte changé par l'application) le vide ;
 * - garde-fou : une lecture réelle date au plus de [ttlMs]. Une application qui changerait son texte sans déplacer le curseur
 *   (cas rare) ne peut donc pas laisser un texte périmé plus longtemps.
 *
 * Logique pure (horloge injectée), testée en JVM (`InputTextCacheTest`).
 */
class InputTextCache(
    private val clock: () -> Long,
    private val ttlMs: Long = TTL_MS,
) {

    // Texte avant le curseur : [before] lu (ou mis à jour) sur une fenêtre de [beforeWindow] caractères demandés. Plus court
    // que la fenêtre : le début du champ est atteint, le cache sert alors toute demande plus grande.
    private var before: String? = null
    private var beforeWindow = 0

    private var after: String? = null
    private var afterWindow = 0

    // Texte sélectionné : null = aucune sélection (connu si [selectionKnown]).
    private var selectionKnown = false
    private var selected: CharSequence? = null

    /** Moment de la dernière lecture réelle (les mises à jour locales ne le repoussent pas). */
    private var readAt = Long.MIN_VALUE

    // Sélection connue (dernière notification ou position calculée après nos écritures) ; -1 : inconnue.
    private var selStart = UNKNOWN
    private var selEnd = UNKNOWN

    /** Positions du curseur attendues après nos écritures, dans l'ordre, pas encore confirmées par une notification. */
    private val pendingEchoes = ArrayDeque<Int>()

    /** Nouveau champ (ou champ relancé) : rien n'est connu, sauf la sélection initiale si l'éditeur la donne. */
    fun reset(initialSelStart: Int = UNKNOWN, initialSelEnd: Int = UNKNOWN) {
        clearText()
        pendingEchoes.clear()
        selStart = initialSelStart
        selEnd = initialSelEnd
    }

    fun textBefore(n: Int, read: (Int) -> CharSequence?): CharSequence? {
        if (!fresh()) clearText()
        before?.let { cached ->
            if (n <= cached.length) return cached.substring(cached.length - n)
            if (cached.length < beforeWindow) return cached // début du champ atteint
        }
        val window = maxOf(n, MIN_WINDOW)
        val text = read(window) ?: return null
        val value = text.toString()
        before = value
        beforeWindow = window
        readAt = clock()
        return if (n >= value.length) value else value.substring(value.length - n)
    }

    fun textAfter(n: Int, read: (Int) -> CharSequence?): CharSequence? {
        if (!fresh()) clearText()
        after?.let { cached ->
            if (n <= cached.length) return cached.substring(0, n)
            if (cached.length < afterWindow) return cached // fin du champ atteinte
        }
        val window = maxOf(n, MIN_WINDOW)
        val text = read(window) ?: return null
        val value = text.toString()
        after = value
        afterWindow = window
        readAt = clock()
        return if (n >= value.length) value else value.substring(0, n)
    }

    fun selectedText(read: () -> CharSequence?): CharSequence? {
        if (!fresh()) clearText()
        if (selectionKnown) return selected
        val text = read()
        selected = text
        selectionKnown = true
        readAt = clock()
        return text
    }

    /** `commitText(text, newCursorPosition)` envoyé au champ. */
    fun onCommit(text: CharSequence, newCursorPosition: Int) {
        val start = minOf(selStart, selEnd)
        if (newCursorPosition != 1 || start < 0) {
            onOtherWrite()
            return
        }
        // Le texte remplace la sélection (s'il y en a une) : ce qui la précède reste avant le curseur, ce qui la suit après.
        before?.let { cached ->
            before = cached + text
            beforeWindow += text.length
        }
        selected = null
        selectionKnown = true // après un commitText, plus rien n'est sélectionné
        moveVirtualCursor(start + text.length)
    }

    /** `deleteSurroundingText(beforeLength, afterLength)` envoyé au champ, curseur sans sélection. */
    fun onDeleteSurrounding(beforeLength: Int, afterLength: Int) {
        val cursor = selStart
        if (cursor < 0 || selStart != selEnd || beforeLength < 0 || afterLength < 0) {
            onOtherWrite()
            return
        }
        before?.let { cached ->
            val atStart = cached.length < beforeWindow
            val kept = when {
                beforeLength <= cached.length -> cached.dropLast(beforeLength)
                atStart -> "" // tout ce qui précède le curseur est effacé
                else -> null
            }
            before = kept
            // Fenêtre recalculée pour garder la règle « plus court que la fenêtre = début du champ atteint ».
            beforeWindow = if (kept == null) 0 else kept.length + if (atStart) 1 else 0
        }
        after?.let { cached ->
            val atEnd = cached.length < afterWindow
            val kept = when {
                afterLength <= cached.length -> cached.drop(afterLength)
                atEnd -> ""
                else -> null
            }
            after = kept
            afterWindow = if (kept == null) 0 else kept.length + if (atEnd) 1 else 0
        }
        moveVirtualCursor(cursor - minOf(beforeLength, cursor))
    }

    /** Toute autre écriture (touche, sélection, action de l'éditeur, contenu riche…) : plus rien n'est sûr. */
    fun onOtherWrite() {
        clearText()
        pendingEchoes.clear()
        selStart = UNKNOWN
        selEnd = UNKNOWN
    }

    /** Notification de déplacement du curseur (`onUpdateSelection`). */
    fun onSelectionUpdate(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        val composing = candidatesStart != -1 || candidatesEnd != -1
        val echo = if (!composing && newSelStart == newSelEnd) pendingEchoes.indexOf(newSelStart) else -1
        if (echo >= 0) {
            // Écho d'une de nos écritures : le cache est déjà à jour (ou en avance, si d'autres écritures ont suivi).
            repeat(echo + 1) { pendingEchoes.removeFirst() }
            if (pendingEchoes.isEmpty()) {
                selStart = newSelStart
                selEnd = newSelEnd
            }
            return
        }
        clearText()
        pendingEchoes.clear()
        selStart = newSelStart
        selEnd = newSelEnd
    }

    private fun moveVirtualCursor(cursor: Int) {
        selStart = cursor
        selEnd = cursor
        if (pendingEchoes.size >= MAX_PENDING_ECHOES) pendingEchoes.removeFirst()
        pendingEchoes.addLast(cursor)
    }

    private fun fresh(): Boolean = clock() - readAt <= ttlMs

    private fun clearText() {
        before = null
        beforeWindow = 0
        after = null
        afterWindow = 0
        selectionKnown = false
        selected = null
    }

    companion object {
        /** Durée maximale de confiance dans une lecture réelle (la frappe continue s'appuie sur les mises à jour locales). */
        const val TTL_MS = 500L

        /** Taille minimale d'une lecture : la fenêtre des suggestions et de la majuscule automatique (TEXT_CONTEXT_LOOKBEHIND). */
        const val MIN_WINDOW = 50

        private const val MAX_PENDING_ECHOES = 32
        private const val UNKNOWN = -1
    }
}
