package fr.junade.taipo.ai

/**
 * Garde-fou contre les réponses vides ou tronquées du modèle de correction.
 *
 * Les petits modèles locaux répondent parfois avec seulement le début du texte (typiquement le
 * premier paragraphe quand on leur en envoie deux). Sans contrôle, cette réponse remplaçait tout
 * le texte d'origine dans le champ et le reste était effacé. Fonction pure, testable
 * indépendamment de l'IME.
 */
object CorrectionSafeguard {

    /** En dessous de cette longueur, on ne juge pas le ratio : un texte très court peut changer beaucoup. */
    private const val MIN_LENGTH_TO_CHECK = 20

    /** Une correction d'orthographe ne raccourcit pas un texte de plus de 40 %. */
    private const val MIN_LENGTH_RATIO = 0.6

    /**
     * Renvoie [corrected] (sans espaces en bordure) s'il est plausible comme correction de
     * [original], sinon null : le texte d'origine doit alors être conservé tel quel.
     */
    fun accept(original: String, corrected: String): String? {
        val candidate = corrected.trim()
        if (candidate.isEmpty()) return null
        if (original.length >= MIN_LENGTH_TO_CHECK && candidate.length < original.length * MIN_LENGTH_RATIO) return null
        if (original.count { it == '\n' } > candidate.count { it == '\n' }) return null
        return candidate
    }
}
