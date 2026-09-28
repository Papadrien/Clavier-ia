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
     * caractères, la rangée du bas étant la plus proche du doigt) : accents d'une lettre
     * (story 1.8) ou symboles du point. Vide si la touche n'a pas de bulle d'appui long.
     */
    val popup: List<List<Char>> = emptyList(),
    /**
     * Chiffre des touches du haut quand la rangée de chiffres est désactivée (story 1.6), affiché
     * en petit indice sur la touche. Un appui long le saisit directement, sauf si la touche a une
     * bulle [popup] (story 1.8) : le chiffre en est alors le premier choix.
     */
    val longPressChar: Char? = null,
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

    /**
     * Story 1.8 : caractères proposés par un appui long sur une lettre, du plus courant au moins
     * courant (le premier est celui qu'on veut le plus souvent). Français : accents français
     * d'abord ; anglais : accents des emprunts (café, naïve, señor...).
     */
    private val accentsFr: Map<Char, String> = mapOf(
        'a' to "àâæáäãåā",
        'c' to "çćč",
        'e' to "éèêëēę",
        'i' to "îïíìī",
        'l' to "ł",
        'n' to "ñń",
        'o' to "ôœöòóõøō",
        's' to "ßśš",
        'u' to "ùûüúū",
        'y' to "ÿý",
        'z' to "žźż",
    )

    private val accentsEn: Map<Char, String> = mapOf(
        'a' to "áàâäæãåā",
        'c' to "çćč",
        'e' to "éèêëēę",
        'i' to "íìîïī",
        'l' to "ł",
        'n' to "ñń",
        'o' to "óòôöœõøō",
        's' to "ßśš",
        'u' to "úùûüū",
        'y' to "ýÿ",
        'z' to "žźż",
    )

    /** Nombre maximal de caractères par rangée de la bulle (au-delà, la bulle passe sur 2 rangées). */
    private const val POPUP_MAX_COLUMNS = 5

    /**
     * Range [items] (par ordre de priorité) en grille : la première rangée, celle des choix les
     * plus courants, est en bas, au plus près du doigt.
     */
    private fun popupGrid(items: List<Char>): List<List<Char>> =
        items.chunked(POPUP_MAX_COLUMNS).reversed()

    /** Inverse de [popupGrid] : les caractères par ordre de priorité. */
    private fun popupItems(popup: List<List<Char>>): List<Char> = popup.reversed().flatten()

    /** Rangée du bas : le ! et le ? sont accessibles par appui long sur le point, ce qui agrandit l'espace. */
    private val punctuationRow = listOf(
        Key("comma", ",", KeyAction.TypeChar(','), secondary = true),
        Key("space", "", KeyAction.Space, 4.8f),
        Key("period", ".", KeyAction.TypeChar('.'), secondary = true, popup = periodPopup),
        Key("enter", "⏎", KeyAction.Enter, 1.8f),
    )

    /** Disposition AZERTY (français), sans les chiffres en appui long (voir [letters]). */
    private val lettersBase = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "azertyuiop".map { letter(it, accentsFr) },
            "qsdfghjklm".map { letter(it, accentsFr) },
            listOf(shiftKey(1.4f)) + "wxcvbn".map { letter(it, accentsFr) } + listOf(apostrophe, backspace),
            listOf(toggleLetters) + punctuationRow,
        ),
    )

    /** Disposition QWERTY (anglais), décision 1.1, sans les chiffres en appui long. */
    private val lettersEnBase = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "qwertyuiop".map { letter(it, accentsEn) },
            "asdfghjkl".map { letter(it, accentsEn) },
            listOf(shiftKey()) + "zxcvbnm".map { letter(it, accentsEn) } + listOf(backspace),
            listOf(toggleLetters) + punctuationRow,
        ),
    )

    /**
     * Story 1.6 : quand la rangée de chiffres est désactivée, les 10 touches de la rangée du haut
     * saisissent 1 à 0 (dans l'ordre) par appui long, comme sur Gboard. Story 1.8 : quand la touche
     * a aussi des accents, le chiffre est le premier choix de la bulle, suivi des accents.
     */
    private fun KeyboardLayout.withTopRowLongPressDigits() = copy(
        rows = listOf(
            rows.first().mapIndexed { i, key ->
                val digit = NUMBER_ROW_CHARS[i]
                key.copy(
                    longPressChar = digit,
                    popup = if (key.popup.isEmpty()) key.popup else popupGrid(listOf(digit) + popupItems(key.popup)),
                )
            },
        ) + rows.drop(1),
    )

    /** Disposition AZERTY (français) : rangée de chiffres désactivée, chiffres en appui long. */
    val letters = lettersBase.withTopRowLongPressDigits()

    /** Disposition QWERTY (anglais) : rangée de chiffres désactivée, chiffres en appui long. */
    val lettersEn = lettersEnBase.withTopRowLongPressDigits()

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
    private const val NUMBER_ROW_CHARS = "1234567890"
    private val numberRow: List<Key> = NUMBER_ROW_CHARS.map(::digit)

    /**
     * Variantes pré-construites (évite de reconstruire un layout à chaque frappe). Elles partent
     * des bases sans appui long : avec la rangée de chiffres, les chiffres sont déjà à l'écran.
     */
    private val lettersWithNumberRow = lettersBase.withNumberRow()
    private val lettersEnWithNumberRow = lettersEnBase.withNumberRow()

    private fun KeyboardLayout.withNumberRow() = copy(rows = listOf(numberRow) + rows)

    /**
     * Layout à afficher. [numberRow] (story 1.5) ajoute la rangée de chiffres
     * en haut du clavier de lettres ; il est sans effet sur le clavier
     * symboles, qui contient déjà ses propres chiffres. Sans rangée de chiffres
     * (story 1.6), les chiffres restent accessibles via la bascule `123` et par
     * appui long sur les touches du haut.
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

    private fun letter(c: Char, accents: Map<Char, String> = emptyMap()): Key =
        Key(
            "letter_$c",
            c.toString(),
            KeyAction.TypeChar(c),
            popup = accents[c]?.let { popupGrid(it.toList()) }.orEmpty(),
        )

    private fun digit(c: Char): Key =
        Key("digit_$c", c.toString(), KeyAction.TypeChar(c))

    private fun symbol(c: Char): Key =
        Key("symbol_$c", c.toString(), KeyAction.TypeChar(c))
}