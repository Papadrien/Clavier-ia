package fr.junade.taipo

/**
 * Espaces à mettre autour d'un texte inséré d'un bloc au curseur (« Ajouter le texte » du chat) : sans elles, la réponse
 * se colle au mot ou à l'emoji qui précède (« 🍇La mer… ») ou au mot qui suit. Logique pure, testée en JVM.
 */
object InsertSpacing {

    /** Caractères après lesquels le texte inséré se colle : ouverture de parenthèse, de guillemet, apostrophe, trait d'union. */
    private const val NO_SPACE_AFTER = "([{«“‘\"'’-/@#"

    /** Ponctuation qui se colle au mot précédent : l'insertion qui commence par elle ne prend pas d'espace devant. */
    private const val NO_SPACE_BEFORE = ",.;:!?…)]}»”’"

    /**
     * [text] entouré des espaces nécessaires : [before] est le caractère juste avant le point d'insertion (null en début
     * de champ), [after] celui juste après (null en fin de champ). Un blanc au bord de [text] tient lieu d'espace.
     */
    fun around(before: Char?, text: String, after: Char?): String {
        if (text.isEmpty()) return text
        // Un texte qui commence (ou finit) déjà par un blanc garde le sien : pas d'espace en double (« Merci.  À bientôt. »).
        val prefix = if (before != null && !before.joins(NO_SPACE_AFTER) && !text.first().joins(NO_SPACE_BEFORE)) " " else ""
        val suffix = if (after != null && !after.joins(NO_SPACE_BEFORE) && !text.last().joins(NO_SPACE_AFTER)) " " else ""
        return prefix + text + suffix
    }

    /** Ce caractère se colle au texte voisin : blanc, ou caractère de [set]. */
    private fun Char.joins(set: String) = isWhitespace() || this in set
}
