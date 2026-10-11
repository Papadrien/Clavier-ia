package fr.junade.taipo.suggestion

import java.util.Locale

/**
 * Règles de lecture des adresses e-mail dans le texte tapé, partagées par l'apprentissage des habitudes
 * (`HabitSignals`) et la bande de suggestions. Logique pure (aucune dépendance Android), testée en JVM.
 *
 * Une adresse est lue de façon volontairement simple : lettres ASCII, chiffres et `. _ % + -` avant le « @ »,
 * domaine à points avec une extension d'au moins deux lettres. Une adresse enregistrée est toujours en minuscules
 * (la casse n'a pas de sens pour une adresse). Le texte fourni est une fenêtre de quelques dizaines de caractères
 * avant le curseur : quand elle est coupée au début ([truncated]), un jeton qui touche ce début est peut-être
 * incomplet et n'est jamais lu.
 */
object EmailText {

    /** Longueur maximale d'une adresse retenue (les adresses plus longues sont des identifiants, pas des adresses à retaper). */
    const val MAX_LENGTH = 100

    private val SHAPE = Regex("[a-z0-9._%+\\-]+@([a-z0-9]([a-z0-9\\-]*[a-z0-9])?\\.)+[a-z]{2,}")

    // Ponctuation qui peut suivre une adresse sans en faire partie : « (jean@x.fr), », « <jean@x.fr> », « "jean@x.fr" »...
    private const val TRAILING_PUNCTUATION = ",;:!?)]}>\"'\u00BB\u201D\u2019"

    /** Caractère qui peut composer une adresse en cours de frappe (le « @ » compris). */
    fun isEmailChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '%' || c == '+' || c == '-' || c == '@'

    /**
     * L'adresse en minuscules si [text] en est une complète et valide, null sinon. Sert aussi à vérifier ce qui
     * vient d'être lu dans le texte avant de l'enregistrer.
     */
    fun normalize(text: String): String? {
        val email = text.trim().lowercase(Locale.ROOT)
        if (email.length > MAX_LENGTH || !SHAPE.matches(email)) return null
        val local = email.substringBefore('@')
        if (local.startsWith('.') || local.endsWith('.') || email.contains("..")) return null
        return email
    }

    /**
     * Le jeton qui touche le curseur : la suite de caractères d'adresse qui termine [textBeforeCursor] (l'adresse
     * en cours de frappe, complète ou non). Null s'il n'y en a pas, si le jeton touche le début d'une fenêtre
     * [truncated] (début peut-être manquant) ou s'il est collé à une lettre non ASCII (« éjean@x.fr »).
     */
    fun trailingToken(textBeforeCursor: String, truncated: Boolean = false): String? {
        var start = textBeforeCursor.length
        while (start > 0 && isEmailChar(textBeforeCursor[start - 1])) start--
        if (start == textBeforeCursor.length) return null
        if (start == 0 && truncated) return null
        if (start > 0 && textBeforeCursor[start - 1].isLetterOrDigit()) return null
        return textBeforeCursor.substring(start)
    }

    /**
     * L'adresse (en minuscules) que [textBeforeCursor] vient de terminer : la dernière adresse valide du texte,
     * espaces et ponctuation de fin ignorés (« écris à jean@x.fr, » donne « jean@x.fr »). Null si le texte ne se
     * termine pas par une adresse complète.
     */
    fun completedEmail(textBeforeCursor: String, truncated: Boolean = false): String? {
        var end = textBeforeCursor.length
        while (end > 0 && (textBeforeCursor[end - 1].isWhitespace() || textBeforeCursor[end - 1] in TRAILING_PUNCTUATION)) end--
        val token = trailingToken(textBeforeCursor.substring(0, end), truncated) ?: return null
        // Un point final est la ponctuation de la phrase, pas de l'adresse.
        return normalize(token.trimEnd('.'))
    }
}
