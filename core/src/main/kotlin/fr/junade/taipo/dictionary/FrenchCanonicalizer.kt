package fr.junade.taipo.dictionary

/**
 * Canoniseur français (phase 1 de la refonte de l'autocorrecteur) : normalise les formes avant de les comparer au
 * lexique. Logique pure, testée en JVM.
 *
 * - **Apostrophes** : l'apostrophe typographique (’ U+2019, ʼ, ‘, ′) vaut l'apostrophe droite des listes. Remplacement
 *   caractère pour caractère : les positions du texte ne changent pas, on peut donc chercher dans la copie normalisée
 *   et modifier le texte d'origine aux mêmes indices.
 * - **Ligatures** : « œ » s'écrit « oe » sur la plupart des claviers (« coeur », « soeur », « oeuvre »). Les listes de
 *   fréquence (sous-titres) contiennent les deux écritures, souvent à égalité : le dictionnaire seul ne dit pas laquelle
 *   est juste. La règle est portée par les données, voir [ligatureRestorations].
 */
object FrenchCanonicalizer {

    private const val TYPOGRAPHIC_APOSTROPHES = "’ʼ‘′"

    /**
     * La forme œ doit peser au moins ce rapport (ici 1/3) de la forme « oe » pour la remplacer : « cœur » (98 % de « coeur »)
     * oui ; « phœnix » (8 % de « phoenix »), « mœlleux » (0,7 % de « moelleux ») non.
     */
    private const val LIGATURE_SHARE_DIVISOR = 3L

    /** Le texte avec l'apostrophe droite à la place des apostrophes typographiques (même longueur). */
    fun normalizeApostrophes(text: String): String {
        if (text.none { it in TYPOGRAPHIC_APOSTROPHES }) return text
        return String(CharArray(text.length) { if (text[it] in TYPOGRAPHIC_APOSTROPHES) '\'' else text[it] })
    }

    /** « cœur » -> « coeur », « Œil » -> « Oeil » : l'écriture sans ligature. */
    fun unligature(word: String): String = if ('œ' in word || 'Œ' in word) word.replace("œ", "oe").replace("Œ", "Oe") else word

    /**
     * Pour chaque candidat avec ligature, son écriture sans ligature -> la forme avec ligature (« coeur » -> « cœur »),
     * à condition que la forme avec ligature pèse au moins 1/3 de l'autre (voir [LIGATURE_SHARE_DIVISOR]). Seuls les
     * mots que les listes écrivent vraiment avec la ligature sont concernés : « poète », « moelle », « coefficient » ne
     * changent jamais, faute de forme « œ » dans les listes.
     *
     * [frequencyOf] : fréquence d'un mot dans les listes (0 s'il n'y figure pas).
     */
    fun ligatureRestorations(
        words: Array<String>,
        frequencies: LongArray,
        frequencyOf: (String) -> Long,
    ): Map<String, String> {
        val result = HashMap<String, String>()
        for (i in words.indices) {
            val word = words[i]
            if ('œ' !in word) continue
            val plain = unligature(word)
            if (frequencies[i] * LIGATURE_SHARE_DIVISOR >= frequencyOf(plain)) result[plain] = word
        }
        return result
    }
}
