package fr.junade.taipo.ai

/** Un échange de la conversation de génération : le prompt envoyé au modèle et sa réponse (éventuellement partielle). */
data class ChatExchange(val prompt: String, val response: String)

/**
 * Met en forme l'historique d'une conversation pour le rejouer dans une conversation LiteRT-LM neuve
 * (après un stop, une correction entre deux prompts ou un changement de modèle). Choix volontaire :
 * l'historique passe dans le texte du premier tour, ce qui n'exige que `sendMessageAsync`, au lieu de
 * l'API des messages initiaux de `ConversationConfig`, qui n'a jamais été essayée sur
 * litertlm-android 0.17.1 (choix conservé : le rejeu par le texte fonctionne sur appareil).
 */
object ChatTranscript {

    /** Renvoie [prompt] tel quel si [history] est vide, sinon l'historique suivi du nouveau [prompt]. */
    fun build(history: List<ChatExchange>, prompt: String): String {
        if (history.isEmpty()) return prompt
        return buildString {
            append("Voici les échanges précédents de cette conversation.\n")
            for (exchange in history) {
                append("\nUtilisateur : ").append(exchange.prompt)
                append("\nAssistant : ").append(exchange.response)
                append('\n')
            }
            append("\nNouveau message de l'utilisateur : ").append(prompt)
        }
    }
}
