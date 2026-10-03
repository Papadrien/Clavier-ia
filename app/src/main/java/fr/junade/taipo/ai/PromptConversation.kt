package fr.junade.taipo.ai

/** État de la réponse du modèle dans un échange du mode prompt. */
enum class PromptMessageStatus { IN_PROGRESS, COMPLETED, INTERRUPTED }

/**
 * Un échange affiché dans la zone de chat : le prompt de l'utilisateur et la réponse (éventuellement partielle).
 *
 * Story 5.2 : [fieldContext] est le texte du champ de l'application joint à ce tour (null si rien n'a été
 * joint) ; [sentPrompt] est le texte réellement envoyé au modèle (contexte + prompt), null quand c'est
 * le prompt seul. La bulle n'affiche que [prompt] ; l'historique rejoué au modèle utilise [modelPrompt].
 */
data class PromptMessage(
    val prompt: String,
    val response: String,
    val status: PromptMessageStatus,
    val fieldContext: String? = null,
    val sentPrompt: String? = null,
) {
    /** Ce que le modèle a reçu pour ce tour. */
    val modelPrompt: String get() = sentPrompt ?: prompt
}

/**
 * Story 5.1, phase 5.1-3 : la conversation du mode prompt (décisions 9, 11 et 14). Elle est portée
 * par `ClavierIme` et vit jusqu'à la fermeture du clavier ; la sortie du mode prompt ne l'efface pas.
 *
 * - Un seul échange peut être en cours, toujours le dernier.
 * - Un stop fige la réponse partielle déjà reçue : elle reste dans la bulle et dans l'historique
 *   envoyé au modèle (décision 11).
 * - [history] ne contient jamais l'échange en cours, et laisse de côté les échanges sans réponse
 *   (stop avant le premier morceau, erreur) : le modèle n'a rien dit, il n'y a rien à lui rejouer.
 *
 * Logique pure (sans Android), testée en JVM. Pas thread-safe : à manipuler depuis le thread principal.
 */
class PromptConversation {

    private val items = mutableListOf<PromptMessage>()

    /** Copie des échanges, du plus ancien au plus récent. */
    val messages: List<PromptMessage>
        get() = items.toList()

    /** Vrai tant qu'aucun prompt n'a été envoyé : le bouton afficher/masquer le chat n'existe alors pas (décisions 5 et 14). */
    val isEmpty: Boolean
        get() = items.isEmpty()

    val isGenerating: Boolean
        get() = items.lastOrNull()?.status == PromptMessageStatus.IN_PROGRESS

    /**
     * Texte du champ que le modèle a reçu en dernier : celui du dernier échange qui est entré dans
     * l'historique (réponse non vide) avec un contexte joint. Un échange en cours, retiré ou interrompu
     * avant toute réponse ne compte pas : le modèle ne l'a pas retenu (il n'est pas rejoué).
     */
    fun lastSentContext(): String? =
        items.lastOrNull {
            it.status != PromptMessageStatus.IN_PROGRESS && it.response.isNotBlank() && it.fieldContext != null
        }?.fieldContext

    /**
     * Décision 15 : le contexte à joindre au prochain prompt. [prepared] est le texte du champ déjà
     * préparé par [FieldContext.prepare] (null : champ vide, rien n'est joint). Il n'est joint que s'il
     * diffère de ce que le modèle a déjà reçu ; sinon null.
     */
    fun contextToAttach(prepared: String?): String? =
        prepared?.takeIf { it != lastSentContext() }

    /**
     * Ouvre un nouvel échange en cours pour [prompt] (espaces de bord retirés). Refuse (faux) si le
     * prompt est vide ou si une génération est déjà en cours. [fieldContext], s'il est donné (voir
     * [contextToAttach]), est joint au tour avec l'introduction [contextHeader] (story 5.2).
     */
    fun start(prompt: String, fieldContext: String? = null, contextHeader: String = ""): Boolean {
        val text = prompt.trim()
        if (text.isEmpty() || isGenerating) return false
        val sent = if (fieldContext != null) FieldContext.compose(contextHeader, fieldContext, text) else null
        items.add(PromptMessage(text, "", PromptMessageStatus.IN_PROGRESS, fieldContext, sent))
        return true
    }

    /** Ajoute un morceau de réponse à l'échange en cours ; sans effet s'il n'y en a pas. */
    fun appendResponse(chunk: String) {
        if (!isGenerating || chunk.isEmpty()) return
        val last = items.last()
        items[items.lastIndex] = last.copy(response = last.response + chunk)
    }

    /**
     * Termine l'échange en cours. [text], s'il est donné, remplace la réponse accumulée (c'est le
     * texte final du moteur) ; [completed] faux signifie une génération interrompue, dont la
     * réponse partielle est conservée. Sans effet s'il n'y a pas d'échange en cours.
     */
    fun finish(text: String? = null, completed: Boolean) {
        if (!isGenerating) return
        val last = items.last()
        items[items.lastIndex] = last.copy(
            response = text ?: last.response,
            status = if (completed) PromptMessageStatus.COMPLETED else PromptMessageStatus.INTERRUPTED,
        )
    }

    fun complete() = finish(completed = true)

    /** Stop ou croix « annuler » pendant la génération : fige la réponse partielle (décision 11). */
    fun interrupt() = finish(completed = false)

    /**
     * Retire l'échange en cours (erreur, aucun modèle) et rend son prompt, pour le remettre dans la
     * zone de saisie. Null s'il n'y a pas d'échange en cours.
     */
    fun discardInProgress(): String? {
        if (!isGenerating) return null
        return items.removeAt(items.lastIndex).prompt
    }

    /** Historique à envoyer au modèle avec le prochain prompt (décisions 9 et 11). */
    fun history(): List<ChatExchange> =
        items
            .filter { it.status != PromptMessageStatus.IN_PROGRESS && it.response.isNotBlank() }
            .map { ChatExchange(it.modelPrompt, it.response.trim()) }

    /** Efface tout : fermeture du clavier (décision 14). */
    fun clear() {
        items.clear()
    }
}
