package fr.junade.taipo.ai

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * Réglages d'inférence des réécritures en un tour (correction, changement de ton, traduction), pour des réponses plus rapides
 * (optimisation du 21/11/2026) :
 *
 * - décodage glouton ([GREEDY] : le mot le plus probable à chaque pas) au lieu de l'échantillonnage par défaut du modèle : la
 *   réponse attendue est une version du texte, pas une création. Plus stable, et sans tirage au sort à chaque mot ;
 * - plafond de longueur de la réponse ([maxOutputTokens]) : un petit modèle qui part en boucle ou ajoute un commentaire
 *   s'arrête tôt, au lieu de générer jusqu'à la limite du contexte (plusieurs dizaines de secondes sur processeur).
 *
 * Le mode Chat garde l'échantillonnage du modèle (réponses plus variées) et pas de plafond.
 */
object InferenceTuning {

    /** topK = 1 : toujours le jeton le plus probable (la température et le tirage n'ont alors plus d'effet). */
    val GREEDY = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0)

    /** Marge du plafond : réponse courte (texte d'un mot) ou un peu plus longue que l'original (ton, traduction). */
    private const val MIN_OUTPUT_TOKENS = 64

    /** Un jeton vaut au moins ~2 caractères dans les langues du clavier (souvent 3 à 4) : plafond large pour le texte attendu. */
    private const val CHARS_PER_TOKEN = 2

    /** Plafond de jetons de la réponse, pour un texte d'entrée de [inputChars] caractères. Logique pure, testée en JVM. */
    fun maxOutputTokens(inputChars: Int): Int = inputChars / CHARS_PER_TOKEN + MIN_OUTPUT_TOKENS

    /** Conversation d'une réécriture : consigne [systemPrompt], décodage glouton, réponse plafonnée d'après [inputChars]. */
    fun rewriteConversation(systemPrompt: String, inputChars: Int): ConversationConfig = ConversationConfig(
        systemInstruction = Contents.of(systemPrompt),
        samplerConfig = GREEDY,
        maxOutputToken = maxOutputTokens(inputChars),
    )
}
