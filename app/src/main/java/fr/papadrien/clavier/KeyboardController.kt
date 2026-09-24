package fr.papadrien.clavier

data class KeyboardState(
    val activeLayout: LayoutId = LayoutId.LETTERS,
    val isShifted: Boolean = false,
)

data class KeyPressResult(
    val commit: String? = null,
    val deleteBefore: Int = 0,
    val isEnter: Boolean = false,
    val newState: KeyboardState,
)

class KeyboardController(initialState: KeyboardState = KeyboardState()) {

    var state: KeyboardState = initialState
        private set

    fun reset() {
        state = KeyboardState()
    }

    fun onKey(key: Key): KeyPressResult {
        return when (val action = key.action) {
            is KeyAction.TypeChar -> {
                val c = if (state.isShifted && action.char.isLetter()) action.char.uppercaseChar() else action.char
                state = state.copy(isShifted = false)
                KeyPressResult(commit = c.toString(), newState = state)
            }

            KeyAction.Shift -> {
                state = state.copy(isShifted = !state.isShifted)
                KeyPressResult(newState = state)
            }

            KeyAction.Backspace -> {
                KeyPressResult(deleteBefore = 1, newState = state)
            }

            KeyAction.Enter -> {
                state = state.copy(isShifted = false)
                KeyPressResult(isEnter = true, newState = state)
            }

            KeyAction.Space -> {
                state = state.copy(isShifted = false)
                KeyPressResult(commit = " ", newState = state)
            }

            KeyAction.ToggleLayout -> {
                state = state.copy(
                    activeLayout = if (state.activeLayout == LayoutId.LETTERS) LayoutId.SYMBOLS else LayoutId.LETTERS,
                    isShifted = false,
                )
                KeyPressResult(newState = state)
            }
        }
    }
}