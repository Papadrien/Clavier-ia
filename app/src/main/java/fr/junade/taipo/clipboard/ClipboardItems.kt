package fr.junade.taipo.clipboard

/**
 * Stories 2.5 à 2.8 : cartes du panneau Presse-papiers, règles d'épinglage, de modification et d'étiquette.
 *
 * Le panneau montre les copies récentes (story 2.9) : la dernière copie (mémoire vive du clavier)
 * puis les lignes de l'historique, de la plus récente à la plus ancienne ; ensuite les éléments
 * épinglés, du plus récent au plus ancien. Un texte n'apparaît qu'une fois : un texte déjà épinglé
 * n'est montré que comme carte épinglée (l'épinglé prime), et la dernière copie n'est pas répétée
 * par sa ligne d'historique. Une ligne d'historique de plus d'1 h n'est pas montrée.
 *
 * Story 2.10 : une copie ou une ligne d'historique est sensible si l'application source l'a signalée
 * ou si son texte ressemble à un numéro de carte ([SensitiveContentDetector]), calculé à chaque
 * construction. Les éléments épinglés ne sont jamais masqués rétroactivement : ils l'ont été
 * volontairement, avant ou sans détection.
 *
 * Logique pure (sans Android), testée en JVM.
 */
object ClipboardItems {

    /** Taille maximale d'un élément épinglé (caractères). */
    const val MAX_PINNED_CHARS = 10_000

    /** Story 2.7 : longueur maximale d'une étiquette (caractères, après retrait des espaces autour). */
    const val MAX_LABEL_CHARS = 15

    /** Nombre maximal d'éléments épinglés. */
    const val MAX_PINNED = 50

    /** Story 2.9 : nombre maximal de copies gardées dans l'historique. */
    const val MAX_HISTORY = 20

    /** Story 2.9 : taille maximale d'une copie de l'historique (au-delà, elle reste en mémoire du clavier seulement). */
    const val MAX_HISTORY_CHARS = 10_000

    /** Story 2.9 : une copie reste dans l'historique 1 h après avoir été copiée. */
    const val HISTORY_RETENTION_MILLIS = 60 * 60 * 1000L

    enum class PinResult { PINNED, ALREADY_PINNED, FULL, TOO_LONG, EMPTY, SENSITIVE }

    /** Story 2.6 : résultat de la modification d'un élément épinglé ([NOT_FOUND] : supprimé entre-temps). */
    enum class EditResult { SAVED, EMPTY, TOO_LONG, NOT_FOUND }

    /** Story 2.7 : résultat de l'enregistrement d'une étiquette ([NOT_FOUND] : élément supprimé entre-temps). */
    enum class LabelResult { SAVED, EMPTY, TOO_LONG, NOT_FOUND }

    /**
     * Une carte du panneau : [pinnedId] est l'identifiant en base pour un élément épinglé (null
     * sinon) ; [isLastClip] vaut vrai pour la dernière copie, épinglée ou non ; [label] est
     * l'étiquette d'un élément épinglé (story 2.7), null s'il n'en a pas. [historyId] est la ligne
     * d'historique (story 2.9) qui porte ce même texte, null s'il n'y en a pas : une ligne
     * d'historique non épinglée et qui n'est pas la dernière copie est une carte « récente » ordinaire.
     */
    data class Item(
        val text: String,
        val sensitive: Boolean,
        val pinnedId: Long?,
        val isLastClip: Boolean,
        val label: String? = null,
        val historyId: Long? = null,
    ) {
        val pinned: Boolean get() = pinnedId != null
    }

    /**
     * Cartes du panneau : la dernière copie, les lignes d'[history] de moins d'1 h à [nowMillis]
     * (de la plus récente à la plus ancienne), puis les éléments [pinned]. Voir la description de
     * l'objet pour les règles de dédoublonnage.
     */
    fun build(
        lastClip: ClipboardSuggestionState.Suggestion?,
        pinned: List<PinnedClip>,
        history: List<ClipHistoryEntry> = emptyList(),
        nowMillis: Long = 0L,
        retentionMillis: Long = HISTORY_RETENTION_MILLIS,
    ): List<Item> {
        val pinnedTexts = pinned.mapTo(HashSet()) { it.text }
        val historyIdByText = HashMap<String, Long>()
        history.forEach { historyIdByText.putIfAbsent(it.text, it.id) }
        val items = ArrayList<Item>(history.size + pinned.size + 1)
        val shown = HashSet<String>()
        if (lastClip != null && lastClip.text !in pinnedTexts) {
            items += Item(lastClip.text, isSensitive(lastClip.text, lastClip.sensitive), pinnedId = null, isLastClip = true, historyId = historyIdByText[lastClip.text])
            shown += lastClip.text
        }
        history
            .filter { nowMillis - it.copiedAtMillis < retentionMillis }
            .sortedWith(compareByDescending<ClipHistoryEntry> { it.copiedAtMillis }.thenByDescending { it.id })
            .forEach {
                if (it.text !in pinnedTexts && shown.add(it.text)) {
                    items += Item(it.text, isSensitive(it.text, it.sensitive), pinnedId = null, isLastClip = false, historyId = it.id)
                }
            }
        pinned.forEach {
            items += Item(
                it.text,
                sensitive = false,
                pinnedId = it.id,
                isLastClip = it.text == lastClip?.text,
                label = it.label,
                historyId = historyIdByText[it.text],
            )
        }
        return items
    }

    /** Story 2.9 : vrai si [text] ne va pas dans l'historique (vide, ou plus long que [MAX_HISTORY_CHARS]). */
    fun historyRefusal(text: String): Boolean = text.isBlank() || text.length > MAX_HISTORY_CHARS

    /** Story 2.10 : [text] est sensible si l'application source l'a signalé ([flagged]) ou si son contenu l'est. */
    fun isSensitive(text: String, flagged: Boolean): Boolean = flagged || SensitiveContentDetector.looksSensitive(text)

    /**
     * Raison pour laquelle [text] ne peut pas être épinglé, ou null s'il peut l'être (doublon et
     * plafond : voir la base). Story 2.10 : refusé aussi si son contenu est détecté sensible, même
     * sans drapeau de l'application source.
     */
    fun pinRefusal(text: String, sensitive: Boolean): PinResult? = when {
        isSensitive(text, sensitive) -> PinResult.SENSITIVE
        text.isBlank() -> PinResult.EMPTY
        text.length > MAX_PINNED_CHARS -> PinResult.TOO_LONG
        else -> null
    }

    /**
     * Story 2.6 : raison pour laquelle [text] ne peut pas être enregistré comme texte modifié, ou
     * null. Un doublon avec un autre élément épinglé est autorisé (contrairement à l'épinglage).
     */
    fun editRefusal(text: String): EditResult? = when {
        text.isBlank() -> EditResult.EMPTY
        text.length > MAX_PINNED_CHARS -> EditResult.TOO_LONG
        else -> null
    }

    /**
     * Story 2.6 : « Modifier » est proposé pour un élément épinglé et pour la dernière copie, sauf
     * si elle est sensible (son aperçu est masqué : l'éditeur la montrerait en clair) ou plus
     * longue que [MAX_PINNED_CHARS] (limite du texte enregistré).
     */
    fun canEdit(item: Item): Boolean = !item.sensitive && item.text.length <= MAX_PINNED_CHARS

    /** Story 2.7 : l'étiquette telle qu'elle est enregistrée (espaces autour retirés, casse conservée). */
    fun normalizeLabel(raw: String): String = raw.trim()

    /**
     * Story 2.7 : raison pour laquelle [raw] ne peut pas être enregistré comme étiquette, ou null.
     * Une étiquette identique à celle d'un autre élément est autorisée. Pour retirer une étiquette,
     * voir la story 2.8 ([PinnedClipRepository.clearLabel]).
     */
    fun labelRefusal(raw: String): LabelResult? {
        val label = normalizeLabel(raw)
        return when {
            label.isEmpty() -> LabelResult.EMPTY
            label.length > MAX_LABEL_CHARS -> LabelResult.TOO_LONG
            else -> null
        }
    }

    /** Story 2.7 : seuls les éléments épinglés portent une étiquette (la dernière copie non épinglée n'en a pas). */
    fun canLabel(item: Item): Boolean = item.pinned
}
