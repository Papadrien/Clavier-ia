package fr.junade.taipo

/**
 * Langue du clavier à partir du réglage de l'utilisateur : une langue choisie explicitement, ou « automatique »
 * (null) = la première langue de la liste du système que le clavier gère, sinon l'anglais. En mode multilingue
 * (au moins deux langues cochées), la langue active est l'une d'entre elles. Logique pure, testée en JVM.
 */
object KeyboardLanguageChoice {

    /** Langue de repli quand ni le réglage ni aucune langue du système n'est gérée. */
    val FALLBACK: KeyboardLanguage = KeyboardLanguage.EN

    /**
     * @param systemLanguageTags langues du système par ordre de préférence (ex. `["ja-JP", "fr-FR"]`).
     * @param multilingual langues du clavier multilingue (vide si le mode est désactivé) ; avec au moins deux langues,
     * elles l'emportent sur [chosen] et sur la langue du système.
     * @param activeMultilingual dernière langue utilisée en mode multilingue (si elle n'est plus dans la liste, la
     * première langue de la liste est retenue).
     */
    fun resolve(
        chosen: KeyboardLanguage?,
        systemLanguageTags: List<String>,
        multilingual: List<KeyboardLanguage> = emptyList(),
        activeMultilingual: KeyboardLanguage? = null,
    ): KeyboardLanguage {
        if (MultilingualChoice.isActive(multilingual)) {
            return activeMultilingual?.takeIf { it in multilingual } ?: multilingual.first()
        }
        return chosen ?: systemLanguageTags.firstNotNullOfOrNull { KeyboardLanguage.fromTag(it) } ?: FALLBACK
    }
}

/** Clavier multilingue : liste de langues cochées par l'utilisateur et changement circulaire de l'une à l'autre. */
object MultilingualChoice {

    /** Le mode ne s'active qu'avec au moins deux langues. */
    const val MIN_LANGUAGES = 2

    fun isActive(languages: List<KeyboardLanguage>): Boolean = languages.size >= MIN_LANGUAGES

    /** Langue suivante dans la liste (retour à la première après la dernière), ou null si le mode n'est pas actif. */
    fun next(languages: List<KeyboardLanguage>, current: KeyboardLanguage): KeyboardLanguage? {
        if (!isActive(languages)) return null
        val index = languages.indexOf(current)
        return languages[(index + 1) % languages.size] // index = -1 (langue hors liste) → première langue
    }

    /** « fr,en » : codes séparés par des virgules, dans l'ordre de l'énumération, sans doublon ni code inconnu. */
    fun encode(languages: Collection<KeyboardLanguage>): String =
        KeyboardLanguage.entries.filter { it in languages }.joinToString(",") { it.tag }

    fun decode(stored: String?): List<KeyboardLanguage> {
        if (stored.isNullOrBlank()) return emptyList()
        val wanted = stored.split(',').mapNotNull { KeyboardLanguage.fromTag(it.trim()) }.toSet()
        return KeyboardLanguage.entries.filter { it in wanted }
    }
}
