package fr.junade.taipo.dictionary

/**
 * Règles du dictionnaire personnel (story 1.4) : mots ajoutés manuellement par
 * l'utilisateur (noms propres, jargon, pseudos...) qui ne doivent jamais être
 * corrigés par l'autocorrection locale (story 1.3), et qui servent aussi de
 * candidats de correction.
 *
 * Décision produit : ajout et suppression manuels uniquement, pas
 * d'apprentissage automatique. Les mots sont communs aux deux langues du
 * clavier (un nom propre reste le même en français et en anglais).
 *
 * Objet pur (aucune dépendance Android) : la persistance est faite ailleurs
 * (voir [PersonalDictionaryRepository]), pour rester testable en JVM simple.
 */
object PersonalDictionary {

    /** Résultat d'une tentative d'ajout. */
    enum class AddResult { ADDED, ALREADY_PRESENT, INVALID, FULL }

    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 40

    /** Plafond raisonnable : la recherche de correction parcourt ces mots à chaque fin de mot. */
    const val MAX_WORDS = 2000

    /**
     * Nettoie [raw] : espaces retirés aux extrémités, apostrophe typographique
     * remplacée par l'apostrophe droite (celle que tape le clavier). Retourne
     * null si le résultat n'est pas un mot valide : trop court/long, ou
     * caractères autres que lettres, apostrophe et trait d'union (mêmes
     * caractères que ceux qui composent un mot pour l'autocorrection).
     */
    fun normalize(raw: String): String? {
        val word = raw.trim().replace('\u2019', '\'')
        if (word.length < MIN_LENGTH || word.length > MAX_LENGTH) return null
        if (!word.all { it.isLetter() || it == '\'' || it == '-' }) return null
        if (word.none { it.isLetter() }) return null
        return word
    }

    /** Clé d'unicité d'un mot : insensible à la casse (même règle que [Dictionary]). */
    fun keyOf(word: String): String = word.lowercase()

    /** Ordre d'affichage : alphabétique, sans tenir compte de la casse. */
    fun sortForDisplay(words: Collection<String>): List<String> =
        words.sortedWith(String.CASE_INSENSITIVE_ORDER)
}
