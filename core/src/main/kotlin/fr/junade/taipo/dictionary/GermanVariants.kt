package fr.junade.taipo.dictionary

/**
 * Variantes régionales de l'allemand (phase 6 du plan d'autocorrection allemande).
 *
 * **de-CH (et Liechtenstein)** : l'orthographe suisse n'a pas de « ß », elle écrit « ss » (« Strasse », « weiss », « Gruss »). Les listes de fréquence ne
 * contiennent aucun mot suisse à part : les lexiques de clavier suisses n'ont aucun « ß » sur environ 149 000 entrées. Le dictionnaire suisse est donc le
 * dictionnaire allemand dont tous les « ß » sont remplacés par « ss » au chargement ([swissFrequencies], [swissNames]) : « strasse » devient un mot juste,
 * il n'est plus « corrigé » en « straße ». Rien d'autre ne change (les umlauts sont les mêmes). Les règles de contexte connaissent « weiss » et « bloss »
 * ([GermanConfusions]).
 *
 * **de-AT** : rien pour l'instant. Les quelque 50 000 mots propres au lexique autrichien sont surtout des composés ordinaires (« Abendkleid »,
 * « Abendkasse »), que la phase 4 accepte déjà : cela reflète la composition des lexiques, pas un vocabulaire régional. À vérifier avec un Autrichien
 * (jaenner / Januar, Paradeiser / Tomate, etc.) avant d'ajouter quoi que ce soit.
 */
object GermanVariants {

    /** Régions où l'on écrit « ss » : Suisse et Liechtenstein. [country] : code région à deux lettres (« CH »), casse ignorée ; null ou vide : non. */
    fun usesSwissSpelling(country: String?): Boolean = country?.uppercase() in SWISS_COUNTRIES

    /** [frequencies] avec « ß » -> « ss » dans chaque mot ; deux mots qui deviennent identiques (« masse », « maße ») additionnent leurs fréquences. */
    fun swissFrequencies(frequencies: Map<String, Long>): Map<String, Long> {
        if (frequencies.keys.none { 'ß' in it }) return frequencies
        val merged = LinkedHashMap<String, Long>(frequencies.size)
        for ((word, frequency) in frequencies) {
            val swiss = word.replace("ß", "ss")
            merged[swiss] = (merged[swiss] ?: 0L) + frequency
        }
        return merged
    }

    /** [names] avec « ß » -> « ss » ; un nom qui devient identique à un autre (sans tenir compte de la casse) n'est gardé qu'une fois. */
    fun swissNames(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.map { it.replace("ß", "ss") }.filter { seen.add(it.lowercase()) }
    }

    private val SWISS_COUNTRIES = setOf("CH", "LI")
}
