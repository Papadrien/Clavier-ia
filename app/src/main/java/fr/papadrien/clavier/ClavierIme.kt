package fr.papadrien.clavier

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

class ClavierIme : InputMethodService() {

    private val controller = KeyboardController()
    private lateinit var keyboardView: KeyboardView

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this)
        keyboardView.setOnKeyListener { key -> onKeyPressed(key) }
        applyState()
        return keyboardView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!this::keyboardView.isInitialized) return
        controller.reset()
        applyState()
    }

    private fun onKeyPressed(key: Key) {
        val result = controller.onKey(key)

        result.commit?.let { text -> currentInputConnection?.commitText(text, 1) }
        if (result.deleteBefore > 0) {
            currentInputConnection?.deleteSurroundingText(result.deleteBefore, 0)
        }
        if (result.isEnter) {
            pressEnter()
        }
        applyState()
    }

    private fun pressEnter() {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val isMultiline = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val hasAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (isMultiline || !hasAction) {
            currentInputConnection?.commitText("\n", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun applyState() {
        keyboardView.layout = Keyboards.layoutOf(controller.state.activeLayout)
        keyboardView.isShifted = controller.state.isShifted
    }
}