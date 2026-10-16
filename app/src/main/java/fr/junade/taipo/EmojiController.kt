package fr.junade.taipo

import android.view.View
import android.view.inputmethod.InputConnection
import fr.junade.taipo.emoji.EmojiPanelView
import fr.junade.taipo.emoji.RecentEmojiBarView
import fr.junade.taipo.emoji.RecentEmojis

/**
 * Emojis du clavier (story 1.15), extraits de [TaipoIme] (audit 2.1) : panneau emoji, barre des emojis récents, insertion
 * d'un emoji choisi (panneau, barre des récents, emoji suggéré) et retour arrière du panneau.
 *
 * Le comportement est celui d'avant l'extraction : mêmes appels, dans le même ordre, dans le champ de l'application comme
 * dans le tampon du mode prompt.
 */
class EmojiController(private val host: Host) {

    /** Ce que le contrôleur demande au clavier : vues, champ, prompt, historique d'annulation et état de la barre. */
    interface Host {
        /** Vrai quand les vues du clavier existent (avant, le panneau et la barre n'ont pas encore été créés). */
        fun isComposed(): Boolean

        fun emojiPanel(): EmojiPanelView

        fun recentEmojiBar(): RecentEmojiBarView

        fun keyboardView(): KeyboardView

        fun recentEmojis(): List<String>

        fun setRecentEmojis(emojis: List<String>)

        fun promptActive(): Boolean

        /** Vrai dans un champ de messagerie (la barre des récents n'y est affichée que là). */
        fun messagingField(): Boolean

        fun clipboardPanelVisible(): Boolean

        /** Emoji actuellement proposé dans la barre de suggestions, ou null. */
        fun currentEmojiSuggestion(): String?

        fun isRecording(): Boolean

        fun isCorrectionInProgress(): Boolean

        fun hasSelection(): Boolean

        fun inputConnection(): InputConnection?

        fun haptic()

        fun clearPendingAutocorrection()

        fun onUserTyped()

        fun clearHighlight()

        fun recordUndoEdit(inserted: String, original: String)

        fun learnFromTyping()

        fun clearSuggestions()

        fun syncAutoCapitalization()

        fun applyState()

        fun updateCorrectionBarVisibility()

        /** Mode prompt : insère [text] au curseur du tampon. */
        fun promptInsert(text: String)

        fun promptBackspace()

        /** Mode prompt : texte qui précède le curseur dans le tampon. */
        fun promptTextBeforeCursor(): String

        /** Mode prompt : rafraîchit la zone de saisie du prompt. */
        fun promptRefreshInput()

        /** Mode prompt : suites d'une édition (suggestions, barre) après un emoji suggéré. */
        fun afterPromptEdit()

        /** Supprime la sélection du champ ; faux s'il n'y en a pas. */
        fun deleteSelectedText(): Boolean

        fun deleteLastCluster()
    }

    /** Vrai tant que le panneau emoji est affiché. */
    val isPanelVisible: Boolean
        get() = host.isComposed() && host.emojiPanel().visibility == View.VISIBLE

    fun showPanel() {
        val panel = host.emojiPanel()
        val keyboard = host.keyboardView()
        panel.configure(keyboard.rowHeightPx(), keyboard.bottomInsetPx())
        panel.show(host.recentEmojis())
        keyboard.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
        refreshRecentBar()
        host.clearSuggestions()
    }

    /** Retour aux touches (bouton ABC, nouveau champ, rotation) ; [resync] resynchronise majuscule et barre. */
    fun hidePanel(resync: Boolean = true) {
        val panel = host.emojiPanel()
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        host.keyboardView().visibility = View.VISIBLE
        refreshRecentBar()
        if (resync) {
            host.syncAutoCapitalization()
            host.applyState()
            host.updateCorrectionBarVisibility()
        }
    }

    /** Un emoji choisi dans le panneau est inséré au curseur (il remplace une éventuelle sélection). */
    fun onSelected(emoji: String) {
        host.haptic()
        if (host.promptActive()) {
            host.clearPendingAutocorrection()
            host.promptInsert(emoji)
            host.setRecentEmojis(RecentEmojis.add(host.recentEmojis(), emoji))
            host.learnFromTyping()
            host.promptRefreshInput()
            return
        }
        host.onUserTyped()
        host.clearHighlight()
        host.clearPendingAutocorrection()
        // commitText remplace une éventuelle sélection, comme n'importe quelle saisie.
        val replacedByEmoji = host.inputConnection()?.getSelectedText(0)?.toString().orEmpty()
        host.inputConnection()?.commitText(emoji, 1)
        host.recordUndoEdit(inserted = emoji, original = replacedByEmoji)
        host.setRecentEmojis(RecentEmojis.add(host.recentEmojis(), emoji))
        host.learnFromTyping()
        host.updateCorrectionBarVisibility()
    }

    /**
     * Un emoji de la barre des récents est inséré au curseur (il remplace une éventuelle sélection) et
     * rejoint la tête des récents ; la barre garde son ordre jusqu'à la prochaine ouverture de champ
     * ou du panneau, pour ne pas bouger sous le doigt.
     */
    fun onRecentBarTapped(emoji: String) {
        if (host.isRecording() || host.isCorrectionInProgress()) return
        onSelected(emoji)
        host.syncAutoCapitalization()
        host.applyState()
    }

    /** Retour arrière du panneau emoji : supprime la sélection, sinon le dernier caractère (emoji composé entier). */
    fun onBackspace() {
        host.haptic()
        if (host.promptActive()) {
            host.clearPendingAutocorrection()
            host.promptBackspace()
            host.promptRefreshInput()
            return
        }
        host.onUserTyped()
        host.clearHighlight()
        host.clearPendingAutocorrection()
        if (!host.deleteSelectedText()) host.deleteLastCluster()
        host.updateCorrectionBarVisibility()
    }

    /**
     * Touche sur l'emoji suggéré : il est inséré au curseur, précédé d'une espace si le mot vient
     * d'être tapé sans espace après lui. Il rejoint aussi les emojis récents.
     */
    fun onSuggestionTapped() {
        val emoji = host.currentEmojiSuggestion() ?: return
        if (host.promptActive()) {
            // Même règle que dans le champ : précédé d'une espace si le mot vient d'être tapé sans espace après lui.
            host.haptic()
            host.clearPendingAutocorrection()
            val needsSpace = needsSpaceBefore(host.promptTextBeforeCursor().lastOrNull())
            host.promptInsert(if (needsSpace) " $emoji" else emoji)
            host.setRecentEmojis(RecentEmojis.add(host.recentEmojis(), emoji))
            host.learnFromTyping()
            host.afterPromptEdit()
            return
        }
        if (host.isRecording() || host.isCorrectionInProgress() || host.hasSelection()) return
        val ic = host.inputConnection() ?: return
        host.haptic()
        host.onUserTyped()
        host.clearHighlight()
        host.clearPendingAutocorrection()
        val needsSpace = needsSpaceBefore(ic.getTextBeforeCursor(1, 0)?.lastOrNull())
        val insertedEmoji = if (needsSpace) " $emoji" else emoji
        ic.commitText(insertedEmoji, 1)
        host.recordUndoEdit(inserted = insertedEmoji, original = "")
        host.setRecentEmojis(RecentEmojis.add(host.recentEmojis(), emoji))
        host.learnFromTyping()
        host.syncAutoCapitalization()
        host.applyState()
        host.updateCorrectionBarVisibility()
    }

    /**
     * La barre n'est affichée que dans un champ de messagerie, avec des récents, hors mode prompt. Quand un panneau (emoji,
     * Smart Clipboard) la masque, elle reste INVISIBLE et garde sa place : la hauteur du clavier ne varie pas, donc l'application
     * n'a pas à rescroller (sinon le dernier message ou le curseur passe sous la barre). GONE seulement hors messagerie / mode prompt.
     */
    fun refreshRecentBar() {
        if (!host.isComposed()) return
        val recents = host.recentEmojis()
        val state = recentBarState(
            promptActive = host.promptActive(),
            messagingField = host.messagingField(),
            hasRecents = recents.isNotEmpty(),
            emojiPanelVisible = host.emojiPanel().visibility == View.VISIBLE,
            clipboardPanelVisible = host.clipboardPanelVisible(),
        )
        val bar = host.recentEmojiBar()
        if (state == RecentBarState.VISIBLE) bar.setEmojis(recents)
        bar.visibility = when (state) {
            RecentBarState.VISIBLE -> View.VISIBLE
            RecentBarState.INVISIBLE -> View.INVISIBLE
            RecentBarState.GONE -> View.GONE
        }
    }

    /** État de la barre des récents. */
    enum class RecentBarState { VISIBLE, INVISIBLE, GONE }

    companion object {
        /** Décision 12 : masquée en mode prompt, quelle que soit la règle habituelle. */
        fun recentBarState(
            promptActive: Boolean,
            messagingField: Boolean,
            hasRecents: Boolean,
            emojiPanelVisible: Boolean,
            clipboardPanelVisible: Boolean,
        ): RecentBarState {
            val reserved = !promptActive && messagingField && hasRecents
            val visible = reserved && !emojiPanelVisible && !clipboardPanelVisible
            return when {
                visible -> RecentBarState.VISIBLE
                reserved -> RecentBarState.INVISIBLE
                else -> RecentBarState.GONE
            }
        }

        /** Une espace précède l'emoji si le caractère juste avant le curseur est une lettre ou un chiffre. */
        fun needsSpaceBefore(lastChar: Char?): Boolean = lastChar != null && lastChar.isLetterOrDigit()
    }
}
