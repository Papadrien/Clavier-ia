package fr.junade.taipo.ai

/**
 * Story 5.2 : le texte déjà présent dans le champ de l'application est joint au prompt comme contexte
 * (décisions 15 et 16 du contexte épopée 5). Logique pure (sans Android), testée en JVM.
 *
 *  - [prepare] normalise le texte capturé et le tronque par le début s'il dépasse la limite (décision 16) ;
 *  - [compose] fabrique le texte réellement envoyé au modèle : contexte puis prompt.
 *
 * Le moment d'envoi (« seulement si le texte a changé », décision 15) est décidé par
 * [PromptConversation.contextToAttach], qui connaît ce que le modèle a déjà reçu.
 */
object FieldContext {

    /**
     * Longueur maximale provisoire du contexte, en caractères (≈ 800 à 1 000 jetons en français). Valeur à
     * régler par instrumentation sur le Pixel 9 (point ouvert 6.1) : la plus petite fenêtre de contexte
     * (modèle « Ultra-léger ») et l'historique, où chaque version envoyée s'ajoute, sont les contraintes.
     */
    const val DEFAULT_MAX_CHARS = 3_000

    /** Début de texte signalant au modèle que le début du champ a été coupé (décision 16). */
    private const val CUT_MARKER = "[…] "

    /**
     * Texte du champ prêt à être joint, ou null s'il n'y a rien à joindre (champ absent ou vide).
     * Les espaces de bord sont retirés (une espace tapée en fin de champ ne compte pas comme un
     * changement). Au-delà de [maxChars], on garde la **fin** du texte, la plus récente, précédée de
     * « […] » ; la coupe ne sépare pas une paire de substitution (emoji). La comparaison « texte changé »
     * se fait sur ce texte déjà tronqué (décision 16).
     */
    fun prepare(fieldText: String?, maxChars: Int = DEFAULT_MAX_CHARS): String? {
        require(maxChars > 0) { "maxChars doit être positif" }
        val text = fieldText?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.length <= maxChars) return text
        var start = text.length - maxChars
        if (Character.isLowSurrogate(text[start])) start++ // la coupe tomberait au milieu d'un emoji
        val tail = text.substring(start).trimStart()
        return if (tail.isEmpty()) null else CUT_MARKER + tail
    }

    /**
     * Texte envoyé au modèle pour un tour : [header], le [context] entre guillemets triples, puis le
     * [prompt]. Sans [context] (champ vide ou inchangé), le prompt part seul.
     */
    fun compose(header: String, context: String?, prompt: String): String {
        if (context == null) return prompt
        return buildString {
            append(header).append('\n')
            append("\"\"\"\n").append(context).append("\n\"\"\"\n\n")
            append(prompt)
        }
    }
}
