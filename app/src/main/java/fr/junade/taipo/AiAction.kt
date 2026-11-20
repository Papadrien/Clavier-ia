package fr.junade.taipo

/**
 * Actions IA du panneau IA (20/11/2026) : toutes celles du clavier sauf la dictée Vocal, qui n'est pas une action IA et garde
 * sa place fixe à droite de la barre. Chacune peut être épinglée dans la barre du haut ([AiPins]).
 */
enum class AiAction(val storageKey: String) {
    CORRECT("correct"),
    TONE("tone"),
    TRANSLATE("translate"),
    CHAT("chat"),
    TRANSCRIPTION("transcription"),
    ;

    companion object {
        fun fromStorageKey(key: String): AiAction? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Actions épinglées dans la barre du haut (logique pure, testée en JVM) : deux au plus. L'ordre compte : la première épinglée se
 * place juste à gauche du bouton IA, la seconde à sa gauche (la barre se lit de droite à gauche : Vocal, IA, épinglées).
 *
 * Par défaut (rien d'enregistré) : Corriger et Transcription, pour que la barre garde l'accès direct d'avant le panneau IA (le
 * bouton IA prend la place de Chat).
 */
object AiPins {

    const val MAX = 2

    val DEFAULT: List<AiAction> = listOf(AiAction.CORRECT, AiAction.TRANSCRIPTION)

    /** Valeur enregistrée → liste (null : jamais réglé, donc [DEFAULT] ; chaîne vide : rien d'épinglé). */
    fun parse(stored: String?): List<AiAction> {
        if (stored == null) return DEFAULT
        return stored.split(',').mapNotNull { AiAction.fromStorageKey(it.trim()) }.distinct().take(MAX)
    }

    fun serialize(pins: List<AiAction>): String = pins.joinToString(",") { it.storageKey }

    /** Résultat d'un appui long sur une action du panneau. */
    sealed interface Toggle {
        val pins: List<AiAction>

        data class Pinned(override val pins: List<AiAction>) : Toggle

        data class Unpinned(override val pins: List<AiAction>) : Toggle

        /** Déjà [MAX] actions épinglées : rien ne change, l'utilisateur doit d'abord en retirer une. */
        data class Full(override val pins: List<AiAction>) : Toggle
    }

    fun toggle(pins: List<AiAction>, action: AiAction): Toggle = when {
        action in pins -> Toggle.Unpinned(pins - action)
        pins.size >= MAX -> Toggle.Full(pins)
        else -> Toggle.Pinned(pins + action)
    }

    /**
     * Ordre des actions dans une rangée de la barre, de gauche à droite, juste avant le bouton IA : d'abord celles qui ne sont pas
     * épinglées (cachées d'ordinaire, affichées seulement pendant leur traitement pour servir d'arrêt), puis les épinglées, la
     * première épinglée tout contre le bouton IA.
     */
    fun rowOrder(pins: List<AiAction>): List<AiAction> = AiAction.entries.filter { it !in pins } + pins.asReversed()

    /** Bulle d'astuce du panneau : rien d'épinglé, ou les premières ouvertures du panneau (pour faire connaître l'appui long). */
    fun showsPinTip(pins: List<AiAction>, panelOpenCount: Int): Boolean = pins.isEmpty() || panelOpenCount < TIP_FIRST_OPENINGS

    /** Nombre d'ouvertures du panneau pendant lesquelles l'astuce s'affiche même avec des actions épinglées. */
    const val TIP_FIRST_OPENINGS = 3
}
