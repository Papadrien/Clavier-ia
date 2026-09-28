package fr.junade.taipo

enum class LayoutId {
    LETTERS,
    SYMBOLS,
}

/** Langue active du clavier (décision 1.1 : AZERTY pour le français, QWERTY pour l'anglais). */
enum class KeyboardLanguage {
    FR,
    EN,
}

data class Key(
    val id: String,
    val label: String,
    val action: KeyAction,
    val weight: Float = 1f,
    /** Touches accessoires (ponctuation rapide) stylées comme les touches fonctionnelles (fond foncé). */
    val secondary: Boolean = false,
)

data class KeyboardLayout(
    val id: LayoutId,
    val rows: List<List<Key>>,
)

object Keyboards {

    private val toggleLetters = Key("toggle", "123", KeyAction.ToggleLayout, 1.4f)
    private val toggleSymbols = Key("toggle", "ABC", KeyAction.ToggleLayout, 1.4f)
    /** Le rétrécissement de la rangée médiane est compensé pour que la largeur d'une
     * lettre reste la même sur toutes les rangées (décision : cohérence visuelle avec
     * le clavier système de référence, où shift et backspace ont une largeur quasi
     * identique). */
    private fun shiftKey() = Key("shift", "⇧", KeyAction.Shift, 1.6f)
    private val backspace = Key("backspace", "⌫", KeyAction.Backspace, 1.6f)

    private val punctuationRow = listOf(
        Key("qmark", "?", KeyAction.TypeChar('?'), secondary = true),
        Key("comma", ",", KeyAction.TypeChar(','), secondary = true),
        Key("space", "", KeyAction.Space, 3f),
        Key("period", ".", KeyAction.TypeChar('.'), secondary = true),
        Key("exclam", "!", KeyAction.TypeChar('!'), secondary = true),
        Key("enter", "⏎", KeyAction.Enter, 1.8f),
    )

    /** Disposition AZERTY (français). */
    val letters = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "azertyuiop".map(::letter),
            "qsdfghjklm".map(::letter),
            listOf(shiftKey()) + "wxcvbn".map(::letter) + listOf(backspace),
            listOf(toggleLetters) + punctuationRow,
        ),
    )

    /** Disposition QWERTY (anglais), décision 1.1. */
    val lettersEn = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "qwertyuiop".map(::letter),
            "asdfghjkl".map(::letter),
            listOf(shiftKey()) + "zxcvbnm".map(::letter) + listOf(backspace),
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

    /**
     * Rangée de chiffres (story 1.5) : 1 à 0, comme sur un clavier physique
     * (ordre différent de la rangée de chiffres du clavier symboles, qui
     * commence par 0 pour rester compatible avec son historique).
     */
    private val numberRow: List<Key> = "1234567890".map(::digit)

    /** Variantes pré-construites (évite de reconstruire un layout à chaque frappe). */
    private val lettersWithNumberRow = letters.withNumberRow()
    private val lettersEnWithNumberRow = lettersEn.withNumberRow()

    private fun KeyboardLayout.withNumberRow() = copy(rows = listOf(numberRow) + rows)

    /**
     * Layout à afficher. [numberRow] (story 1.5) ajoute la rangée de chiffres
     * en haut du clavier de lettres ; il est sans effet sur le clavier
     * symboles, qui contient déjà ses propres chiffres.
     */
    fun layoutOf(
        id: LayoutId,
        language: KeyboardLanguage = KeyboardLanguage.FR,
        numberRow: Boolean = false,
    ): KeyboardLayout = when (id) {
        LayoutId.LETTERS -> when (language) {
            KeyboardLanguage.EN -> if (numberRow) lettersEnWithNumberRow else lettersEn
            KeyboardLanguage.FR -> if (numberRow) lettersWithNumberRow else letters
        }
        LayoutId.SYMBOLS -> symbols
    }

    private fun letter(c: Char): Key =
        Key("letter_$c", c.toString(), KeyAction.TypeChar(c))

    private fun digit(c: Char): Key =
        Key("digit_$c", c.toString(), KeyAction.TypeChar(c))

    private fun symbol(c: Char): Key =
        Key("symbol_$c", c.toString(), KeyAction.TypeChar(c))
}