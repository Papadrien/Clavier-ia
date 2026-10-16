package fr.junade.taipo

import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputConnection
import fr.junade.taipo.ai.VoiceField
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.emoji.EmojiPanelView
import fr.junade.taipo.emoji.RecentEmojiBarView
import fr.junade.taipo.model.AiModel

/*
 * Hôtes des contrôleurs du clavier, regroupés ici (audit 2.1, étape 5).
 *
 * Avant : une dizaine d'interfaces `Host` implémentées en objets anonymes dans `TaipoIme`, avec les mêmes méthodes
 * répétées (`showMessage`, `inputConnection`, `isRecording`, `barReady`, `isCorrectionInProgress`, `haptic`...).
 * Maintenant : `TaipoIme` ne fournit qu'un seul contexte commun ([ImeHostContext]) et chaque hôte, une petite classe de
 * ce fichier, s'appuie dessus. Le comportement est inchangé : chaque méthode fait le même appel qu'avant, dans le même ordre.
 */

/**
 * Ce que [TaipoIme] met à la disposition des hôtes : les contrôleurs, les vues, quelques états du champ courant et les
 * actions partagées. Les méthodes à implémentation par défaut sont celles qui se déduisent des autres membres.
 */
internal interface ImeHostContext {

    // Contrôleurs et vues
    val prompt: PromptModeController
    val voice: VoiceController
    val clipboard: ClipboardController
    val emoji: EmojiController
    val correction: CorrectionAiController
    val suggestions: SuggestionController
    val deleteSwipe: DeleteSwipeController
    val undo: UndoController
    val aiTranscription: AiTranscriptionController
    val messagePopup: MessagePopup
    val viewComposer: ImeViewComposer
    val correctionBar: CorrectionBarView
    val keyboardView: KeyboardView
    val keyboardPreferences: KeyboardPreferences

    // État du champ courant et réglages
    val messagingField: Boolean
    val sensitiveField: Boolean
    val lastSelectionStart: Int
    val lastSelectionEnd: Int
    val keyboardLanguage: KeyboardLanguage
    val heightScale: Float

    fun inputConnection(): InputConnection?
    fun isInputViewShown(): Boolean
    fun string(resId: Int): String
    fun hasSelection(): Boolean
    fun activeModel(): AiModel?
    fun personalWords(): List<String>
    fun protectedWordsIn(text: String): List<String>

    // Actions partagées
    fun haptic()
    fun hapticCursorMove()
    fun clearHighlight()
    fun clearPendingAutocorrection()
    fun onUserTyped()
    fun recordUndoEdit(inserted: String, original: String)
    fun recordUndoAction(undo: UndoHistory.Undo)
    fun recordUndoDeletion(
        deleted: String,
        anchor: String,
        before: String,
        mergeable: Boolean,
        offsetProvider: () -> Int,
    )
    fun learnFromTyping(terminator: String)
    fun clearSuggestions()
    fun syncAutoCapitalization()
    fun applyState()
    fun updateCorrectionBarVisibility()
    fun afterPromptEdit()
    fun deleteSelectedText(): Boolean
    fun deleteLastCluster()
    fun insertGeneratedText(text: String): Boolean
    fun replaceFieldText(text: String): Boolean
    fun onWordSuggestionTapped(suggestion: WordSuggestion)
    fun onKeyPressed(key: Key)
    fun onCursorMoved(steps: Int)
    fun onGlobeSelected()
    fun openAppHome()

    // Déduits des membres ci-dessus
    fun showMessage(message: String) = messagePopup.show(message)

    fun barReady(): Boolean = viewComposer.isComposed

    fun isRecording(): Boolean = voice.isRecording

    fun isCorrectionInProgress(): Boolean = correction.inProgress

    /** Après une action qui a pu changer le texte : majuscule automatique, touches, visibilité de la barre. */
    fun resyncKeyboard() {
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }
}

/** Emojis (story 1.15) : voir [EmojiController]. */
internal class EmojiHost(private val ime: ImeHostContext) : EmojiController.Host {
    override fun isComposed(): Boolean = ime.viewComposer.isComposed
    override fun emojiPanel(): EmojiPanelView = ime.viewComposer.emojiPanel
    override fun recentEmojiBar(): RecentEmojiBarView = ime.viewComposer.recentEmojiBar
    override fun keyboardView(): KeyboardView = ime.keyboardView
    override fun recentEmojis(): List<String> = ime.keyboardPreferences.recentEmojis
    override fun setRecentEmojis(emojis: List<String>) {
        ime.keyboardPreferences.recentEmojis = emojis
    }
    override fun promptActive(): Boolean = ime.prompt.active
    override fun messagingField(): Boolean = ime.messagingField
    override fun clipboardPanelVisible(): Boolean = ime.clipboard.isPanelVisible
    override fun currentEmojiSuggestion(): String? = ime.suggestions.currentEmojiSuggestion
    override fun isRecording(): Boolean = ime.isRecording()
    override fun isCorrectionInProgress(): Boolean = ime.isCorrectionInProgress()
    override fun hasSelection(): Boolean = ime.hasSelection()
    override fun inputConnection(): InputConnection? = ime.inputConnection()
    override fun haptic() = ime.haptic()
    override fun clearPendingAutocorrection() = ime.clearPendingAutocorrection()
    override fun onUserTyped() = ime.onUserTyped()
    override fun clearHighlight() = ime.clearHighlight()
    override fun recordUndoEdit(inserted: String, original: String) =
        ime.recordUndoEdit(inserted = inserted, original = original)
    override fun learnFromTyping() = ime.learnFromTyping("")
    override fun clearSuggestions() = ime.clearSuggestions()
    override fun syncAutoCapitalization() = ime.syncAutoCapitalization()
    override fun applyState() = ime.applyState()
    override fun updateCorrectionBarVisibility() = ime.updateCorrectionBarVisibility()
    override fun promptInsert(text: String) {
        ime.prompt.buffer.insert(text)
    }
    override fun promptBackspace() {
        ime.prompt.buffer.backspace()
    }
    override fun promptTextBeforeCursor(): String = ime.prompt.buffer.textBeforeCursor
    override fun promptRefreshInput() {
        ime.prompt.refreshInput()
    }
    override fun afterPromptEdit() = ime.afterPromptEdit()
    override fun deleteSelectedText(): Boolean = ime.deleteSelectedText()
    override fun deleteLastCluster() = ime.deleteLastCluster()
}

/** Smart Clipboard (puce de collage, panneau, épinglés) : voir [ClipboardController]. */
internal class ClipboardHost(private val ime: ImeHostContext) : ClipboardController.Host {
    override fun showMessage(message: String) = ime.showMessage(message)
    override fun inputConnection() = ime.inputConnection()
    override fun haptic() = ime.haptic()
    override fun clearHighlight() = ime.clearHighlight()
    override fun recordUndoEdit(inserted: String, original: String) = ime.recordUndoEdit(inserted, original)
    override fun clearPendingAutocorrection() = ime.clearPendingAutocorrection()
    override fun barReady(): Boolean = ime.barReady()
    override fun collapseMenu() = ime.correctionBar.collapseMenu()
    override fun setClipboardPanelOpen(open: Boolean) = ime.correctionBar.setClipboardPanelOpen(open)
    override fun setPasteSuggestion(preview: String?, sensitive: Boolean) =
        ime.correctionBar.setPasteSuggestion(preview, sensitive)
    override fun isRecording(): Boolean = ime.isRecording()
    override fun isCorrectionInProgress(): Boolean = ime.isCorrectionInProgress()
    override fun isEmojiPanelVisible(): Boolean = ime.emoji.isPanelVisible
    override fun hideEmojiPanel() = ime.emoji.hidePanel(resync = false)
    override fun keyboardBottomInsetPx(): Int = ime.keyboardView.bottomInsetPx()
    override fun setKeyboardVisible(visible: Boolean) {
        ime.keyboardView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
    override fun refreshRecentEmojiBar() = ime.emoji.refreshRecentBar()
    override fun clearSuggestions() = ime.clearSuggestions()
    override fun resyncKeyboard() = ime.resyncKeyboard()
}

/** Suggestions de mots et d'emoji, prédiction du mot suivant, apprentissage local : voir [SuggestionController]. */
internal class SuggestionHost(private val ime: ImeHostContext) : SuggestionController.Host {
    override fun inputConnection() = ime.inputConnection()
    override fun promptActive(): Boolean = ime.prompt.active
    override fun promptTextBeforeCursor(): String = ime.prompt.buffer.textBeforeCursor
    override fun promptTextAfterCursor(): String = ime.prompt.buffer.textAfterCursor
    override fun barReady(): Boolean = ime.barReady()
    override fun language(): KeyboardLanguage = ime.keyboardLanguage
    override fun isRecording(): Boolean = ime.isRecording()
    override fun isCorrectionInProgress(): Boolean = ime.isCorrectionInProgress()
    override fun isEmojiPanelVisible(): Boolean = ime.emoji.isPanelVisible
    override fun isClipboardPanelVisible(): Boolean = ime.clipboard.isPanelVisible
    override fun hasSelection(): Boolean = ime.hasSelection()
    override fun isInputViewShown(): Boolean = ime.isInputViewShown()
    override fun personalWords(): List<String> = ime.personalWords()
    override fun showEmoji(emoji: String?) {
        // En mode prompt, la barre du haut est remplacée par celle du prompt : la bande s'affiche dans sa propre rangée.
        if (ime.prompt.active) ime.prompt.setSuggestionEmoji(emoji) else ime.correctionBar.setEmojiSuggestion(emoji)
    }
    override fun showWords(words: List<WordSuggestion?>) {
        if (ime.prompt.active) ime.prompt.setSuggestionWords(words) else ime.correctionBar.setWordSuggestions(words)
    }
    override fun clearBars() {
        ime.correctionBar.setEmojiSuggestion(null)
        ime.correctionBar.setWordSuggestions(emptyList())
        ime.prompt.clearSuggestionBar()
        ime.clipboard.refreshPasteSuggestion() // dictée, correction ou panneau en cours : la puce disparaît aussi
    }
}

/** Pop-ups d'information et liste des langues : voir [MessagePopup]. */
internal class MessagePopupHost(private val ime: ImeHostContext) : MessagePopup.Host {
    override fun windowToken(): IBinder? =
        if (ime.viewComposer.isComposed && ime.keyboardView.isAttachedToWindow) ime.keyboardView.windowToken else null
}

/** Mode prompt (conversation, génération, zone de chat) : voir [PromptModeController]. */
internal class PromptHost(private val ime: ImeHostContext) : PromptModeController.Host {
    override fun showMessage(message: String) = ime.showMessage(message)
    override fun haptic() = ime.haptic()
    override fun onPromptCursorMoved() {
        ime.hapticCursorMove()
        ime.syncAutoCapitalization()
        ime.applyState()
    }
    override fun isRecording(): Boolean = ime.isRecording()
    override fun cancelVoice() {
        if (ime.voice.isRecording) ime.voice.cancelRecording()
    }
    override fun isCorrectionInProgress(): Boolean = ime.isCorrectionInProgress()
    override fun barReady(): Boolean = ime.barReady()
    override fun setNormalBarVisible(visible: Boolean) {
        ime.correctionBar.visibility = if (visible) View.VISIBLE else View.GONE
    }
    override fun collapseMenu() = ime.correctionBar.collapseMenu()
    override fun hideEmojiPanel() {
        if (ime.viewComposer.isComposed) ime.emoji.hidePanel(resync = false)
    }
    override fun hideClipboardPanel() = ime.clipboard.hidePanel(resync = false)
    override fun refreshRecentEmojiBar() = ime.emoji.refreshRecentBar()
    override fun clearPendingAutocorrection() = ime.clearPendingAutocorrection()
    override fun clearSuggestions() = ime.clearSuggestions()
    override fun cancelDeleteSwipe() = ime.deleteSwipe.cancel()
    override fun syncAutoCapitalization() = ime.syncAutoCapitalization()
    override fun applyState() = ime.applyState()
    override fun updateCorrectionBarVisibility() = ime.updateCorrectionBarVisibility()
    override fun activeModel() = ime.activeModel()
    override fun capturedFieldText(): String? = ime.inputConnection()?.let { ime.correction.captureFieldText(it) }?.text
    override fun learnFromTyping(terminator: String) = ime.learnFromTyping(terminator)
    override fun insertGeneratedText(text: String): Boolean = ime.insertGeneratedText(text)
    override fun replaceFieldText(text: String): Boolean = ime.replaceFieldText(text)
    override fun onWordSuggestionTapped(suggestion: WordSuggestion) = ime.onWordSuggestionTapped(suggestion)
    override fun onEmojiSuggestionTapped() = ime.emoji.onSuggestionTapped()
}

/** Correction IA : voir [CorrectionAiController]. */
internal class CorrectionHost(private val ime: ImeHostContext) : CorrectionAiController.Host {
    override fun showMessage(message: String) = ime.showMessage(message)
    override fun inputConnection() = ime.inputConnection()
    override fun generationBusy(): Boolean = ime.prompt.isGenerating // le moteur est pris par la génération
    override fun activeModel() = ime.activeModel()
    override fun protectedWords(text: String): List<String> = ime.protectedWordsIn(text)
    override fun setCorrectionBarState(state: CorrectionBarState) {
        ime.correctionBar.state = state
    }
    override fun clearSuggestions() = ime.clearSuggestions()
    override fun updateCorrectionBarVisibility() = ime.updateCorrectionBarVisibility()
    override fun syncAutoCapitalization() = ime.syncAutoCapitalization()
    override fun clearPendingAutocorrection() = ime.clearPendingAutocorrection()
    override fun recordUndo(undo: UndoHistory.Undo) = ime.recordUndoAction(undo)
}

/** Bouton Annuler (à droite du bouton Smart Clipboard) : historique des 10 dernières actions, voir [UndoController]. */
internal class UndoHost(private val ime: ImeHostContext) : UndoController.Host {
    override fun inputConnection() = ime.inputConnection()
    override fun showMessage(message: String) = ime.showMessage(message)
    override fun beforeUndo() = ime.clearHighlight()
    override fun afterUndo() {
        ime.clearPendingAutocorrection()
        ime.resyncKeyboard()
    }
    override fun onUndoAvailabilityChanged(canUndo: Boolean) {
        if (ime.viewComposer.isComposed) ime.correctionBar.setUndoAvailable(canUndo)
    }
}

/** Saisie vocale : voir [VoiceController]. */
internal class VoiceHost(private val ime: ImeHostContext) : VoiceController.Host {
    override fun showMessage(message: String) = ime.showMessage(message)
    override fun generationBusy(): Boolean = ime.prompt.generationBusy()
    override fun clearHighlight() = ime.clearHighlight()
    override fun clearSuggestions() = ime.clearSuggestions()
    override fun refreshPasteSuggestion() = ime.clipboard.refreshPasteSuggestion()
    override fun updateCorrectionBarVisibility() = ime.updateCorrectionBarVisibility()
    override fun syncAutoCapitalization() = ime.syncAutoCapitalization()
    override fun onUserTyped() = ime.onUserTyped()
    override fun setVoiceBarState(state: VoiceBarState) {
        ime.correctionBar.voiceState = state
        ime.prompt.setVoiceState(state) // le bouton micro de la barre du prompt suit le même état
    }
    override fun inputConnection() = ime.inputConnection()
    override fun promptActive(): Boolean = ime.prompt.active
    override fun promptVoiceField(): VoiceField = ime.prompt.voiceField
    override fun hasSelection(): Boolean = ime.hasSelection()
    override fun collapseSelectionBeforeInsert() {
        if (ime.hasSelection() && ime.lastSelectionEnd >= 0) {
            ime.inputConnection()?.setSelection(ime.lastSelectionEnd, ime.lastSelectionEnd)
        }
    }
    override fun recordUndoEdit(inserted: String, original: String) = ime.recordUndoEdit(inserted, original)
}

/** Épopée 14 : transcription IA par modèle multimodal, voir [AiTranscriptionController]. */
internal class AiTranscriptionHost(private val ime: ImeHostContext) : AiTranscriptionController.Host {
    override fun showMessage(message: String) = ime.showMessage(message)
    override fun voiceRecording(): Boolean = ime.isRecording()
    override fun aiBusy(): Boolean = ime.prompt.generationBusy() || ime.isCorrectionInProgress()
    override fun activeModel() = ime.activeModel()
    override fun insertText(text: String): Boolean = ime.insertGeneratedText(text)
    override fun setBarState(state: AiTranscriptionBarState) {
        ime.correctionBar.aiTranscriptionState = state
    }
}

/** Glissement de suppression depuis la touche retour arrière (story 1.9) : voir [DeleteSwipeController]. */
internal class DeleteSwipeHost(private val ime: ImeHostContext) : DeleteSwipeController.Host {
    override fun promptActive(): Boolean = ime.prompt.active
    override fun inputConnection(): InputConnection? = ime.inputConnection()
    override fun lastSelectionStart(): Int = ime.lastSelectionStart
    override fun lastSelectionEnd(): Int = ime.lastSelectionEnd
    override fun promptCursor(): Int = ime.prompt.buffer.cursor
    override fun promptTextBeforeCursor(): String = ime.prompt.buffer.textBeforeCursor
    override fun promptDeleteBefore(count: Int) {
        ime.prompt.buffer.deleteBefore(count)
        ime.prompt.refreshInput()
    }
    override fun haptic() = ime.haptic()
    override fun hapticCursorMove() = ime.hapticCursorMove()
    override fun clearHighlight() = ime.clearHighlight()
    override fun clearPendingAutocorrection() = ime.clearPendingAutocorrection()
    override fun onUserTyped() = ime.onUserTyped()
    override fun recordUndoDeletion(
        deleted: String,
        anchor: String,
        before: String,
        mergeable: Boolean,
        offsetProvider: () -> Int,
    ) = ime.recordUndoDeletion(deleted, anchor, before, mergeable, offsetProvider)
    override fun syncAutoCapitalization() = ime.syncAutoCapitalization()
    override fun applyState() = ime.applyState()
    override fun updateCorrectionBarVisibility() = ime.updateCorrectionBarVisibility()
}

/** Ce que les vues du clavier demandent à l'IME : voir [ImeViewComposer]. */
internal class ViewComposerHost(private val ime: ImeHostContext) : ImeViewComposer.Host {
    override fun heightScale(): Float = ime.heightScale
    override fun onKey(key: Key) = ime.onKeyPressed(key)
    override fun onCursorMoved(steps: Int) = ime.onCursorMoved(steps)
    override fun onGlobeSelected() = ime.onGlobeSelected()
    override fun onDeleteSwipeUpdate(words: Int) = ime.deleteSwipe.onUpdate(words)
    override fun onDeleteSwipeRelease() = ime.deleteSwipe.onRelease()
    override fun onDeleteSwipeCancel() = ime.deleteSwipe.onCancel()
    override fun onRecentEmojiClicked(emoji: String) = ime.emoji.onRecentBarTapped(emoji)
    override fun onCorrectClicked() = ime.correction.onCorrectClicked()
    override fun onCancelCorrectionClicked() = ime.correction.onCancelClicked()
    override fun onUndoClicked() {
        // Correction ou dictée en cours : le texte bouge encore, l'annulation attend.
        if (ime.isCorrectionInProgress() || ime.isRecording()) return
        ime.undo.onUndoClicked()
    }
    override fun onVoiceTouch(event: MotionEvent): Boolean = ime.voice.onButtonTouch(event)
    override fun onVoiceClick() = ime.voice.onAccessibilityClick()

    override fun sensitiveField() = ime.sensitiveField

    override fun showSensitiveFieldMessage() = ime.showMessage(ime.string(R.string.sensitive_field_actions_disabled))
    override fun onAiTranscriptionClick() = ime.aiTranscription.onClick()
    override fun onEmojiSuggestionClicked() = ime.emoji.onSuggestionTapped()
    override fun onWordSuggestionClicked(suggestion: WordSuggestion) = ime.onWordSuggestionTapped(suggestion)
    override fun openAppHome() = ime.openAppHome()
    override fun onEmojiSelected(emoji: String) = ime.emoji.onSelected(emoji)
    override fun onEmojiBackspace() = ime.emoji.onBackspace()
    override fun onEmojiPanelClose() = ime.emoji.hidePanel()
    override fun onViewsCreated() = ime.applyState()
}

/** Auto-remplissage en ligne (gestionnaire de mots de passe) : voir [InlineSuggestionsController]. */
internal class InlineSuggestionsHost(private val ime: ImeHostContext) : InlineSuggestionsController.Host {
    override fun isComposed(): Boolean = ime.viewComposer.isComposed
    override fun setInlineSuggestions(views: List<View>) = ime.correctionBar.setInlineSuggestions(views)
}
