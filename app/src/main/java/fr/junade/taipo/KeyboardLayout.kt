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
    /**
     * Symboles proposés par un appui long sur la touche, sous forme de grille (rangées de
     * caractères). Vide si la touche n'a pas d'appui long.
     */
    val popup: List<List<Char>> = emptyList(),
)

data class KeyboardLayout(
    val id: LayoutId,
    val rows: List<List<Key>>,
)

object Keyboards {

    private val toggleLetters = Key("toggle", "123", KeyAction.ToggleLayout, 1.4f)
    private val toggleSymbols = Key("toggle", "ABC", KeyAction.ToggleLayout, 1.4f)
    /**
     * Largeur des touches Maj/Effacer : elle est choisie pour que la largeur d'une lettre reste la
     * même sur toutes les rangées (10 unités par rangée). Sur l'AZERTY, la rangée du bas contient
     * 7 lettres + apostrophe : Maj (1,4) + Effacer (1,6) + 7 lettres = 10.
     */
    private fun shiftKey(weight: Float = 1.6f) = Key("shift", "⇧", KeyAction.Shift, weight)
    private val backspace = Key("backspace", "⌫", KeyAction.Backspace, 1.6f)

    /** Touche apostrophe, uniquement sur le clavier français (entre N et Effacer). */
    private val apostrophe = Key("apostrophe", "'", KeyAction.TypeChar('\''))

    /** Symboles de l'appui long sur la touche point (grille 3 × 6). */
    private val periodPopup: List<List<Char>> = listOf(
        listOf('&', '%', '+', '·', '"', '_'),
        listOf(';', '/', '-', ':', '\'', '@'),
        listOf('(', ')', '#', '!', ',', '?'),
    )

    /** Rangée du bas : le ! et le ? sont accessibles par appui long sur le point, ce qui agrandit l'espace. */
    private val punctuationRow = listOf(
        Key("comma", ",", KeyAction.TypeChar(','), secondary = true),
        Key("space", "", KeyAction.Space, 5.8f),
        Key("period", ".", KeyAction.TypeChar('.'), secondary = true, popup = periodPopup),
        Key("enter", "⏎", KeyAction.Enter, 1.8f),
    )

    /** Disposition AZERTY (français). */
    val letters = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "azertyuiop".map(::letter),
            "qsdfghjklm".map(::letter),
            listOf(shiftKey(1.4f)) + "wxcvbn".map(::letter) + listOf(apostrophe, backspace),
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