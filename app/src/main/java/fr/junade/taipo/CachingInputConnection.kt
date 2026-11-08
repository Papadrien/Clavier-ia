package fr.junade.taipo

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi

/**
 * Connexion au champ de l'application avec ses lectures mises en cache ([InputTextCache]). Renvoyée par
 * `TaipoIme.getCurrentInputConnection()` : tout le code du clavier (IME, contrôleurs, `sendDownUpKeyEvents` d'Android)
 * passe donc par elle sans rien changer d'autre.
 *
 * Seules les lectures simples (sans style, `flags == 0`) sont servies par le cache. Les écritures passent telles quelles et
 * tiennent le cache à jour : `commitText` et `deleteSurroundingText` le mettent à jour sur place, toutes les autres le vident.
 */
class CachingInputConnection(
    /** Connexion d'origine, pour savoir si Android en a fourni une nouvelle (autre champ). */
    val wrapped: InputConnection,
    private val cache: InputTextCache,
) : InputConnectionWrapper(wrapped, false) {

    // ---- Lectures ----

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        if (flags != 0 || n <= 0) super.getTextBeforeCursor(n, flags) else cache.textBefore(n) { super.getTextBeforeCursor(it, 0) }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        if (flags != 0 || n <= 0) super.getTextAfterCursor(n, flags) else cache.textAfter(n) { super.getTextAfterCursor(it, 0) }

    override fun getSelectedText(flags: Int): CharSequence? =
        if (flags != 0) super.getSelectedText(flags) else cache.selectedText { super.getSelectedText(0) }

    // ---- Écritures suivies ----

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val done = super.commitText(text, newCursorPosition)
        if (done && text != null) cache.onCommit(text, newCursorPosition) else cache.onOtherWrite()
        return done
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val done = super.deleteSurroundingText(beforeLength, afterLength)
        if (done) cache.onDeleteSurrounding(beforeLength, afterLength) else cache.onOtherWrite()
        return done
    }

    // ---- Autres écritures : le cache est vidé ----

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = written { super.setComposingText(text, newCursorPosition) }

    override fun setComposingRegion(start: Int, end: Int): Boolean = written { super.setComposingRegion(start, end) }

    override fun finishComposingText(): Boolean = written { super.finishComposingText() }

    override fun commitCompletion(text: CompletionInfo?): Boolean = written { super.commitCompletion(text) }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = written { super.commitCorrection(correctionInfo) }

    override fun setSelection(start: Int, end: Int): Boolean = written { super.setSelection(start, end) }

    override fun performEditorAction(editorAction: Int): Boolean = written { super.performEditorAction(editorAction) }

    override fun performContextMenuAction(id: Int): Boolean = written { super.performContextMenuAction(id) }

    override fun sendKeyEvent(event: KeyEvent?): Boolean = written { super.sendKeyEvent(event) }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        written { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean =
        written { super.commitContent(inputContentInfo, flags, opts) }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = written { super.performPrivateCommand(action, data) }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        written { super.commitText(text, newCursorPosition, textAttribute) }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        written { super.setComposingText(text, newCursorPosition, textAttribute) }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        written { super.replaceText(start, end, text, newCursorPosition, textAttribute) }

    override fun closeConnection() {
        cache.reset()
        super.closeConnection()
    }

    private inline fun written(write: () -> Boolean): Boolean = write().also { cache.onOtherWrite() }
}
