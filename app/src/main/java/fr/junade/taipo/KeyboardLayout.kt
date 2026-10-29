package fr.junade.taipo

enum class LayoutId {
    LETTERS,

    /** Page des symboles (~ ` | • √ π ...) : second panneau, atteint depuis la page des chiffres (touche « ?123 » → retour). */
    SYMBOLS,

    /** Page des chiffres, @ # € _ & - + ( ) / et ponctuation : premier panneau affiché à la bascule depuis les lettres. */
    SYMBOLS_2,

    /** Story 1.18 : pavé numérique des champs numériques et téléphone (aucune bascule vers les lettres). */
    PAD,
}

/** Vrai pour les deux pages du clavier de symboles. */
val LayoutId.isSymbols: Boolean get() = this == LayoutId.SYMBOLS || this == LayoutId.SYMBOLS_2

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
     * Caractère de la bulle [popup] sélectionné d'office à l'ouverture de la bulle : relâcher le
     * doigt sans le bouger le saisit (ex. appui long puis relâchement sur E = é). C'est le
     * choix le plus courant de la touche ; null si aucun choix ne prime (bulle du point).
     */
    val defaultPopupChar: Char? = null,
    /**
     * Chiffre des touches du haut quand la rangée de chiffres est désactivée (story 1.6), affiché
     * en petit indice sur la touche. Un appui long le saisit directement, sauf si la touche a une
     * bulle [popup] (story 1.8) : le chiffre en est alors le premier choix.
     */
    val longPressChar: Char? = null,
)

/** Largeur d'une rangée, en unités de poids des touches. */
internal fun rowUnits(row: List<Key>): Float = row.fold(0f) { acc, key -> acc + key.weight }

data class KeyboardLayout(
    val id: LayoutId,
    val rows: List<List<Key>>,
) {
    /**
     * Largeur de référence de la disposition, en unités de poids : celle de la rangée la plus large (10 pour les
     * lettres et les symboles, 4 pour les pavés). Toutes les rangées partagent la même unité : une touche pèse
     * toujours la même largeur d'écran, quelle que soit sa rangée.
     */
    val referenceUnits: Float = rows.maxOfOrNull { rowUnits(it) } ?: 0f

    /**
     * Marge, en unités, à gauche (et à droite) d'une rangée plus courte que la référence : elle est centrée au lieu
     * d'être étirée (rangée « asdfghjkl » du QWERTY et du QWERTZ, 9 touches au lieu de 10).
     */
    fun rowStartUnits(row: List<Key>): Float {
        val gap = referenceUnits - rowUnits(row)
        return if (gap < ROW_GAP_EPSILON) 0f else gap / 2f
    }

    private companion object {
        /** Tolérance sur les sommes de poids flottants (1,4 + 6 + 1 + 1,6 peut donner 9,999999). */
        const val ROW_GAP_EPSILON = 0.001f
    }
}

object Keyboards {

    private val toggleLetters = Key("toggle", "?123", KeyAction.ToggleLayout, 1.4f)
    private val toggleSymbols = Key("toggle", "ABC", KeyAction.ToggleLayout, 1.4f)

    /**
     * Touches de bascule entre les deux pages de symboles, à la place de Maj (même largeur que Maj sur
     * l'AZERTY : 1,4 + 7 touches + Effacer 1,6 = 10 unités). Elles affichent la page vers laquelle elles mènent.
     */
    private val toPageTwo = Key("symbol_page", "?123", KeyAction.SymbolPage, 1.4f)
    private val toPageOne = Key("symbol_page", "=\\<", KeyAction.SymbolPage, 1.4f)
    /**
     * Largeur des touches Maj/Effacer : elle est choisie pour que la largeur d'une lettre reste la
     * même sur toutes les rangées (10 unités par rangée). Sur l'AZERTY, la rangée du bas contient
     * 6 lettres + apostrophe : Maj (1,4) + Effacer (1,6) + 7 touches = 10. Sur les QWERTY/QWERTZ, elle contient
     * 7 lettres : Maj (1,5) + Effacer (1,5) + 7 lettres = 10.
     */
    private fun shiftKey(weight: Float = 1.5f) = Key("shift", "⇧", KeyAction.Shift, weight)
    private val backspace = Key("backspace", "⌫", KeyAction.Backspace, 1.6f)

    /** Effacer des dispositions QWERTY/QWERTZ (rangée du bas : Maj 1,5 + 7 lettres + Effacer 1,5 = 10 unités). */
    private val backspaceQwerty = backspace.copy(weight = 1.5f)

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

    /** Accents des autres langues (mêmes principes : les plus courants d'abord). */
    private val accentsEs: Map<Char, String> = mapOf(
        'a' to "áàâäã",
        'e' to "éèêë",
        'i' to "íìîï",
        'o' to "óòôöõ",
        'u' to "úüùû",
        'c' to "ç",
    )

    private val accentsDe: Map<Char, String> = mapOf(
        'a' to "äàáâãå",
        'e' to "éèêë",
        'i' to "íìîï",
        'o' to "öòóôõø",
        'u' to "üùúû",
        's' to "ß",
        'c' to "çćč",
        'n' to "ñ",
    )

    private val accentsIt: Map<Char, String> = mapOf(
        'a' to "àáâä",
        'e' to "èéêë",
        'i' to "ìíîï",
        'o' to "òóôö",
        'u' to "ùúûü",
        'c' to "ç",
    )

    private val accentsPt: Map<Char, String> = mapOf(
        'a' to "áàâãä",
        'c' to "ç",
        'e' to "éêèë",
        'i' to "íìîï",
        'o' to "óôõòö",
        'u' to "úüùû",
    )

    private val accentsNl: Map<Char, String> = mapOf(
        'e' to "éëèê",
        'a' to "äáàâ",
        'i' to "ïíìî",
        'o' to "öóòô",
        'u' to "üúùû",
        'c' to "ç",
        'n' to "ñ",
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

    /**
     * Rangée du bas : le ! et le ? sont accessibles par appui long sur le point, ce qui agrandit
     * l'espace. Story 1.15 : le bouton emoji est à gauche de la barre espace et à droite de la
     * virgule, comme sur Gboard ; il prend 1 unité sur l'espace (4,8 → 3,8) pour que la rangée
     * reste à 10 unités. Entrée est une pilule (2,2 unités, 0,4 prise sur l'espace : 3,4).
     */
    private fun punctuationRowWith(popup: List<List<Char>>) = listOf(
        Key("comma", ",", KeyAction.TypeChar(','), secondary = true),
        Key("emoji", "☺", KeyAction.Emoji, secondary = true),
        Key("space", "", KeyAction.Space, 3.4f),
        Key("period", ".", KeyAction.TypeChar('.'), secondary = true, popup = popup),
        Key("enter", "⏎", KeyAction.Enter, 2.2f),
    )

    private val punctuationRow = punctuationRowWith(periodPopup)

    /** Espagnol : « ¿ » et « ¡ » prennent la place de « · » et « _ » dans la bulle du point. */
    private val periodPopupEs: List<List<Char>> = periodPopup.map { row ->
        row.map { c ->
            when (c) {
                '·' -> '¿'
                '_' -> '¡'
                else -> c
            }
        }
    }
    private val punctuationRowEs = punctuationRowWith(periodPopupEs)

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
            listOf(shiftKey()) + "zxcvbnm".map { letter(it, accentsEn) } + listOf(backspaceQwerty),
            listOf(toggleLetters) + punctuationRow,
        ),
    )

    /** QWERTY des langues sans touche supplémentaire (italien, portugais, néerlandais), accents en appui long. */
    private fun qwertyBase(accents: Map<Char, String>) = lettersEnBase.copy(
        rows = listOf(
            "qwertyuiop".map { letter(it, accents) },
            "asdfghjkl".map { letter(it, accents) },
            listOf(shiftKey()) + "zxcvbnm".map { letter(it, accents) } + listOf(backspaceQwerty),
            listOf(toggleLetters) + punctuationRow,
        ),
    )

    /** QWERTY espagnol : touche Ñ à droite du L (la rangée du milieu a 10 touches, comme l'AZERTY), « ¿ » et « ¡ » sur le point. */
    private val lettersEsBase = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "qwertyuiop".map { letter(it, accentsEs) },
            "asdfghjklñ".map { letter(it, accentsEs) },
            listOf(shiftKey()) + "zxcvbnm".map { letter(it, accentsEs) } + listOf(backspaceQwerty),
            listOf(toggleLetters) + punctuationRowEs,
        ),
    )

    /** QWERTZ allemand : Z et Y échangés ; ä, ö, ü et ß en appui long. */
    private val lettersDeBase = KeyboardLayout(
        id = LayoutId.LETTERS,
        rows = listOf(
            "qwertzuiop".map { letter(it, accentsDe) },
            "asdfghjkl".map { letter(it, accentsDe) },
            listOf(shiftKey()) + "yxcvbnm".map { letter(it, accentsDe) } + listOf(backspaceQwerty),
            listOf(toggleLetters) + punctuationRow,
        ),
    )

    /**
     * Story 1.6 : quand la rangée de chiffres est désactivée, les 10 touches de la rangée du haut
     * saisissent 1 à 0 (dans l'ordre) par appui long, comme sur Gboard. Le chiffre est toujours le
     * premier choix d'une bulle, suivi des accents s'il y en a : une touche sans accents affiche une
     * bulle à une seule cellule (le chiffre), présélectionnée pour que relâcher le saisisse.
     */
    private fun KeyboardLayout.withTopRowLongPressDigits() = copy(
        rows = listOf(
            rows.first().mapIndexed { i, key ->
                val digit = NUMBER_ROW_CHARS[i]
                key.copy(
                    longPressChar = digit,
                    popup = popupGrid(listOf(digit) + popupItems(key.popup)),
                    defaultPopupChar = key.defaultPopupChar ?: digit,
                )
            },
        ) + rows.drop(1),
    )

    /** Disposition AZERTY (français) : rangée de chiffres désactivée, chiffres en appui long. */
    val letters = lettersBase.withTopRowLongPressDigits()

    /** Disposition QWERTY (anglais) : rangée de chiffres désactivée, chiffres en appui long. */
    val lettersEn = lettersEnBase.withTopRowLongPressDigits()

    /** Rangée du bas de la page 1 : « < » et « > » remplacent la virgule et le point, l'emoji reste à sa place. */
    private val symbolsBottomRow = listOf(toggleSymbols) + punctuationRow.map { key ->
        when (key.id) {
            "comma" -> Key("less", "<", KeyAction.TypeChar('<'), secondary = true)
            "period" -> Key("greater", ">", KeyAction.TypeChar('>'), secondary = true)
            else -> key
        }
    }

    /** Page des symboles (~ ` | • √ π ...) : second panneau, atteint depuis la page des chiffres [symbolsMore]. */
    val symbols = KeyboardLayout(
        id = LayoutId.SYMBOLS,
        rows = listOf(
            "~`|•√π÷×§Δ".map(::symbol),
            "£¥\$¢^°={}\\".map(::symbol),
            listOf(toPageTwo) + "%©®™✓[]".map(::symbol) + listOf(backspace),
            symbolsBottomRow,
        ),
    )

    /** Page des chiffres, @ # € _ & - + ( ) /, ponctuation courante : premier panneau affiché quand on quitte les lettres. */
    val symbolsMore = KeyboardLayout(
        id = LayoutId.SYMBOLS_2,
        rows = listOf(
            "1234567890".map(::digit),
            "@#€_&-+()/".map(::symbol),
            listOf(toPageOne) + "*\"':;!?".map(::symbol) + listOf(backspace),
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

    /** Dispositions des langues ajoutées après FR et EN : bases sans appui long, puis variantes avec appui long des chiffres ou avec rangée de chiffres. */
    private class LetterSet(base: KeyboardLayout, withLongPressDigits: (KeyboardLayout) -> KeyboardLayout, withNumberRow: (KeyboardLayout) -> KeyboardLayout) {
        val plain: KeyboardLayout = withLongPressDigits(base)
        val numbered: KeyboardLayout = withNumberRow(base)
    }

    private val otherLetterSets: Map<KeyboardLanguage, LetterSet> = mapOf(
        KeyboardLanguage.ES to lettersEsBase,
        KeyboardLanguage.DE to lettersDeBase,
        KeyboardLanguage.IT to qwertyBase(accentsIt),
        KeyboardLanguage.PT to qwertyBase(accentsPt),
        KeyboardLanguage.NL to qwertyBase(accentsNl),
    ).mapValues { (_, base) -> LetterSet(base, { it.withTopRowLongPressDigits() }, { it.withNumberRow() }) }

    /** Disposition des lettres de [language], avec ou sans rangée de chiffres. */
    fun lettersOf(language: KeyboardLanguage, numberRow: Boolean = false): KeyboardLayout = when (language) {
        KeyboardLanguage.FR -> if (numberRow) lettersWithNumberRow else letters
        KeyboardLanguage.EN -> if (numberRow) lettersEnWithNumberRow else lettersEn
        else -> otherLetterSets.getValue(language).let { if (numberRow) it.numbered else it.plain }
    }

    private fun KeyboardLayout.withNumberRow() = copy(rows = listOf(numberRow) + rows)

    /**
     * Story 1.18 : touches qui remplacent la virgule dans les champs e-mail et URL. La virgule reste
     * accessible par l'appui long sur le point (bulle [periodPopup]), comme « @ » et « / ».
     */
    private val emailAtKey = Key("email_at", "@", KeyAction.TypeChar('@'), secondary = true)
    private val urlSlashKey = Key("url_slash", "/", KeyAction.TypeChar('/'), secondary = true)

    private fun padKey(c: Char) = Key("pad_$c", c.toString(), KeyAction.TypeChar(c), secondary = true)

    /** Pavé des champs numériques : chiffres, virgule et point décimaux, moins, espace, entrée en bas à droite. */
    private val numberPad = KeyboardLayout(
        id = LayoutId.PAD,
        rows = listOf(
            listOf(digit('1'), digit('2'), digit('3'), backspace.copy(weight = 1f)),
            listOf(digit('4'), digit('5'), digit('6'), Key("space", "", KeyAction.Space, secondary = true)),
            listOf(digit('7'), digit('8'), digit('9'), padKey('-')),
            listOf(padKey(','), digit('0'), padKey('.'), Key("enter", "⏎", KeyAction.Enter)),
        ),
    )

    /** Pavé des champs téléphone : chiffres, « + », « - », « * » et « # ». */
    private val phonePad = KeyboardLayout(
        id = LayoutId.PAD,
        rows = listOf(
            listOf(digit('1'), digit('2'), digit('3'), backspace.copy(weight = 1f)),
            listOf(digit('4'), digit('5'), digit('6'), padKey('+')),
            listOf(digit('7'), digit('8'), digit('9'), padKey('-')),
            listOf(padKey('*'), digit('0'), padKey('#'), Key("enter", "⏎", KeyAction.Enter)),
        ),
    )

    private data class VariantKey(
        val id: LayoutId,
        val language: KeyboardLanguage,
        val numberRow: Boolean,
        val fieldType: FieldType,
        val bottomRowKeys: BottomRowKeys,
    )

    /** Dispositions e-mail/URL et celles avec touches du bas masquées, construites une fois (layoutOf est appelé à chaque frappe). */
    private val fieldVariants = java.util.concurrent.ConcurrentHashMap<VariantKey, KeyboardLayout>()

    private fun KeyboardLayout.withCommaReplacedBy(replacement: Key) = copy(
        rows = rows.map { row -> row.map { key -> if (key.id == "comma") replacement else key } },
    )

    /**
     * Retire de la rangée du bas les touches emoji, virgule et point désactivées dans les paramètres ; la barre d'espace
     * reprend leur largeur (la rangée reste à 10 unités). La virgule remplacée par « @ » ou « / » dans les champs e-mail et
     * URL n'est pas concernée : cette touche sert à saisir l'adresse, elle reste toujours affichée. Les touches « < » et
     * « > » des symboles et les touches des pavés numériques ne sont pas non plus concernées.
     */
    private fun KeyboardLayout.withBottomRowKeys(keys: BottomRowKeys): KeyboardLayout = copy(
        rows = rows.map { row ->
            val hidden = row.filter { key ->
                (key.id == "emoji" && !keys.emoji) || (key.id == "comma" && !keys.comma) || (key.id == "period" && !keys.period)
            }
            if (hidden.isEmpty() || row.none { it.action == KeyAction.Space }) {
                row
            } else {
                val freedUnits = hidden.fold(0f) { acc, key -> acc + key.weight }
                row.filterNot { it in hidden }.map { key ->
                    if (key.action == KeyAction.Space) key.copy(weight = key.weight + freedUnits) else key
                }
            }
        },
    )

    private fun baseLayoutOf(id: LayoutId, language: KeyboardLanguage, numberRow: Boolean): KeyboardLayout = when (id) {
        LayoutId.LETTERS -> lettersOf(language, numberRow)
        LayoutId.SYMBOLS, LayoutId.PAD -> symbols
        LayoutId.SYMBOLS_2 -> symbolsMore
    }

    /**
     * Layout à afficher. [numberRow] (story 1.5) ajoute la rangée de chiffres
     * en haut du clavier de lettres ; il est sans effet sur le clavier
     * symboles, qui contient déjà ses propres chiffres. Sans rangée de chiffres
     * (story 1.6), les chiffres restent accessibles via la bascule `123` et par
     * appui long sur les touches du haut.
     *
     * Story 1.18 : selon [fieldType], un champ numérique ou téléphone affiche un pavé numérique
     * (quel que soit [id]) ; un champ e-mail ou URL remplace la virgule par « @ » ou « / », sur les
     * claviers de lettres et de symboles.
     *
     * [bottomRowKeys] : touches emoji, virgule et point à afficher dans la rangée du bas (paramètres du clavier) ;
     * sans effet sur les pavés numériques.
     */
    fun layoutOf(
        id: LayoutId,
        language: KeyboardLanguage = KeyboardLanguage.FR,
        numberRow: Boolean = false,
        fieldType: FieldType = FieldType.TEXT,
        bottomRowKeys: BottomRowKeys = BottomRowKeys.ALL,
    ): KeyboardLayout {
        val replacement = when (fieldType) {
            FieldType.NUMBER -> return numberPad
            FieldType.PHONE -> return phonePad
            FieldType.EMAIL -> emailAtKey
            FieldType.URL -> urlSlashKey
            FieldType.TEXT, FieldType.PASSWORD, FieldType.TERMINAL -> null
        }
        val base = baseLayoutOf(id, language, numberRow)
        if (replacement == null && bottomRowKeys.isAll) return base
        return fieldVariants.getOrPut(VariantKey(base.id, language, numberRow, fieldType, bottomRowKeys)) {
            val withField = if (replacement == null) base else base.withCommaReplacedBy(replacement)
            if (bottomRowKeys.isAll) withField else withField.withBottomRowKeys(bottomRowKeys)
        }
    }

    private fun letter(c: Char, accents: Map<Char, String> = emptyMap()): Key =
        Key(
            "letter_$c",
            c.toString(),
            KeyAction.TypeChar(c),
            popup = accents[c]?.let { popupGrid(it.toList()) }.orEmpty(),
            defaultPopupChar = accents[c]?.first(),
        )

    private fun digit(c: Char): Key =
        Key("digit_$c", c.toString(), KeyAction.TypeChar(c))

    private fun symbol(c: Char): Key =
        Key("symbol_$c", c.toString(), KeyAction.TypeChar(c))
}