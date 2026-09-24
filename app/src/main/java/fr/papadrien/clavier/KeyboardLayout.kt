package fr.papadrien.clavier

enum class LayoutId {
    LETTERS,
    SYMBOLS,
}

data class Key(
    val id: String,
    val label: String,
    val action: KeyAction,
    val weight: Float = 1f,
)

data class KeyboardLayout(
    val id: LayoutId,
    val rows: List<List<Key>>,
)

object Keyboards {

    private val toggleLetters = Key("toggle", "123", KeyAction.ToggleLayout, 1.4f)
    private val toggleSymbols = Key("toggle", "ABC", KeyAction.ToggleLayout, 1.4f)
    private val backspace = Key("backspace", "⌫", KeyAction.Backspace, 1.6f)

    private val punctuationRow = listOf(
        Key("qmark", "?", KeyAction.TypeChar('?')),
        Key("comma", ",", KeyAction.TypeChar(',')),
        Key("space", "", KeyAction.Space, 3f),
        Key("period", ".", KeyAction.TypeChar('.')),
        Key("exclam", "!", KeyAction.TypeChar('!')),
        Key("enter", "⏎", KeyAction.Enter, 1.8f),
    )

    val letters = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "azertyuiop".map(::letter),
            "qsdfghjklm".map(::letter),
            listOf(Key("shift", "⇧", KeyAction.Shift, 1.2f)) + "wxcvbn".map(::letter) + listOf(backspace),
            listOf(toggleLetters) + punctuationRow,
        ),
    )

    val symbols = KeyboardLayout(
        id = LayoutId.SYMBOLS,
        rows = listOf(
            "0123456789".map(::digit),
            "&é\"'(-è_çà".map(::symbol),
            listOf(
                Key("rparen", ")", KeyAction.TypeChar(')')),
                Key("equal", "=", KeyAction.TypeChar('=')),
                Key("at", "@", KeyAction.TypeChar('@')),
                Key("plus", "+", KeyAction.TypeChar('+')),
                Key("asterisk", "*", KeyAction.TypeChar('*')),
                Key("hash", "#", KeyAction.TypeChar('#')),
                Key("dollar", "$", KeyAction.TypeChar('$')),
                Key("percent", "%", KeyAction.TypeChar('%')),
                Key("euro", "€", KeyAction.TypeChar('€'), 0.8f),
                Key("backspace", "⌫", KeyAction.Backspace, 1.6f),
            ),
            listOf(toggleSymbols) + punctuationRow,
        ),
    )

    fun layoutOf(id: LayoutId): KeyboardLayout = when (id) {
        LayoutId.LETTERS -> letters
        LayoutId.SYMBOLS -> symbols
    }

    private fun letter(c: Char): Key =
        Key("letter_$c", c.toString(), KeyAction.TypeChar(c))

    private fun digit(c: Char): Key =
        Key("digit_$c", c.toString(), KeyAction.TypeChar(c))

    private fun symbol(c: Char): Key =
        Key("symbol_$c", c.toString(), KeyAction.TypeChar(c))
}