package fr.junade.taipo.emoji

/**
 * Emojis récemment utilisés (story 1.15), du plus récent au plus ancien, sans doublon. Logique pure
 * (sans Android) : la persistance est dans KeyboardPreferences. Rien n'est appris ni analysé : c'est
 * seulement la liste des derniers emojis choisis, comme sur Gboard.
 */
object RecentEmojis {

    const val MAX_SIZE = 32

    /** Un emoji ne contient jamais de retour à la ligne : il sert de séparateur de stockage. */
    private const val SEPARATOR = "\n"

    /** [emoji] passe en tête ; s'il était déjà présent, son ancienne place est retirée. */
    fun add(current: List<String>, emoji: String, maxSize: Int = MAX_SIZE): List<String> =
        (listOf(emoji) + current.filter { it != emoji }).take(maxSize)

    fun encode(emojis: List<String>): String = emojis.joinToString(SEPARATOR)

    fun decode(raw: String?): List<String> =
        if (raw.isNullOrEmpty()) emptyList() else raw.split(SEPARATOR).filter { it.isNotEmpty() }
}
