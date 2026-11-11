package fr.junade.taipo.dictionary

/**
 * Quand une forme sans accent déjà présente dans les listes est tenue pour une faute du corpus plutôt que pour un mot
 * (voir `Dictionary.dominatedByAccented`) : « ca » contre « ça », « despues » contre « después ».
 *
 * La forme sans accent est corrigée si sa version accentuée est au moins [dominanceFor] fois plus fréquente. Ce seuil
 * dépend de la langue, car elle décide de la part de mots justes que l'on « corrige » à tort :
 * - français : 2 fois pour la plupart des paires (« ecoute » / « écoute »), 10 fois pour une paire « e / é » finale
 *   (« tue » / « tué », où la forme sans accent est un verbe conjugué) ;
 * - espagnol : 10 fois pour toutes les paires. L'accent y sert souvent à distinguer deux mots justes (« esta » / « está »,
 *   « paso » / « pasó », « quien » / « quién », « estas » / « estás ») : au seuil de 2, environ 2 400 mots justes des listes
 *   (dont « esta », « paso », « cual ») auraient été remplacés à la frappe. À 10, les paires restantes sont des oublis
 *   d'accent presque sûrs (« tambien », « aqui », « asi », « mas »), sauf quelques mots justes listés dans
 *   [keptPlain] (« papa » la pomme de terre, « seria » l'adjectif, « sabia »).
 *
 * - allemand : umlauts et ß. Les deux formes d'une paire sont souvent des mots justes (« konnte » / « könnte », « schon » / « schön »,
 *   « wurde » / « würde ») : un umlaut n'est rétabli sur un mot présent dans la liste que s'il est au moins 50 fois plus fréquent
 *   ([softDominance]), sauf [keptPlain] (« ware » la marchandise, « schone » l'impératif, « massig »). Pour ß, la forme en « ss » n'est
 *   presque jamais un mot juste hors Suisse (« strasse », « heiss », « grosse ») : seuil de 10 ([strictDominance], paires reconnues par
 *   [strictPair]). Un mot absent de la liste est rétabli d'office (« uber » -> « über », « fuer » -> « für », « Strasse » -> « Straße »,
 *   voir [transliteration]) : le nettoyage de de.txt (phase 1) en a retiré les formes sans umlaut.
 *
 * [keptPlain] : formes sans accent qui sont de vrais mots, jamais corrigées pour leur accent, quel que soit le rapport.
 * [strictPair] : paires pour lesquelles on applique [strictDominance] au lieu de [softDominance] (l'ordre des deux seuils dépend de la langue).
 * [transliteration] : une forme tapée en transcription (« fuer », « koennen ») est aussi rattachée au mot à umlaut (allemand seulement).
 * [nounsKeepValidWords] : la liste de noms (majuscule, voir `Dictionary.properNameForm`) ne remplace jamais un mot présent dans les listes pour son
 *   seul umlaut ou ß (« hohe » reste « hohe », jamais « Höhe ») ; allemand seulement, où la liste contient des milliers de noms communs.
 */
class AccentPolicy(
    val softDominance: Long,
    val strictDominance: Long,
    val keptPlain: Set<String>,
    private val strictPair: (plain: String, accented: String) -> Boolean,
    val transliteration: Boolean = false,
    val nounsKeepValidWords: Boolean = false,
) {

    /** Rapport minimal de fréquence (accentuée / sans accent) pour que [plain] soit corrigé en [accented]. */
    fun dominanceFor(plain: String, accented: String): Long =
        if (strictPair(plain, accented)) strictDominance else softDominance

    companion object {

        /** Vrai si [accented] ne diffère de [plain] que par un « é » final (ou avant un « s » final) : « tue » / « tué ». */
        private fun isParticipleVariant(plain: String, accented: String): Boolean {
            if (plain.length != accented.length) return false
            val index = if (plain.endsWith("s")) plain.length - 2 else plain.length - 1
            return index >= 0 && plain[index] == 'e' && accented == plain.substring(0, index) + "é" + plain.substring(index + 1)
        }

        /** Comportement d'origine, inchangé : français (et langues sans calibrage propre). « une tache » n'est pas « une tâche ». */
        val FRENCH = AccentPolicy(
            softDominance = 2L,
            strictDominance = 10L,
            keptPlain = setOf("tache", "taches"),
            strictPair = ::isParticipleVariant,
        )

        /**
         * Allemand : seuil de 50 pour les umlauts, 10 pour ß ; mots justes listés dans [keptPlain] (ceux qui, d'après de.txt et les
         * lexiques de clavier, sont de vrais mots dont la forme à umlaut domine à 10 contre 1 ou plus, ou dont les deux formes ss / ß
         * sont justes : masse / maße, busse / buße). La suite (konnte / könnte, wurde / würde...) est l'affaire du contexte (phase 5).
         */
        val GERMAN = AccentPolicy(
            softDominance = 50L,
            strictDominance = 10L,
            keptPlain = setOf(
                "ware", "hort", "horte", "horten", "schone", "schoner", "schonen", "hohle", "balle", "wanden", "kampfer", "baren", "tragt",
                "erhalt", "anhalt", "store",
                "masse", "massen", "busse", "bussen", "massig", "massige", "massigen", "massiger", "strass", "russ", "assen", "mass",
            ),
            strictPair = { _, accented -> 'ß' in accented },
            transliteration = true,
            nounsKeepValidWords = true,
        )

        /**
         * Italien : l'accent n'est écrit que sur la dernière syllabe (« perché », « città », « più ») ; la forme sans accent est presque toujours
         * une faute, sauf les mots courts dont les deux formes sont justes (e / è, da / dà, si / sì, la / là, li / lì, ne / né, se / sé, te / tè,
         * di / dì) et papa / papà, meta / metà. Seuil de 10, comme l'espagnol.
         */
        val ITALIAN = AccentPolicy(
            softDominance = 10L,
            strictDominance = 10L,
            keptPlain = setOf("e", "da", "si", "la", "li", "ne", "se", "te", "di", "papa", "meta"),
            strictPair = { _, _ -> true },
        )

        /**
         * Portugais : comme l'espagnol, l'accent distingue souvent deux mots justes (esta / está, e / é, a / à, nos / nós, pais / país, pode /
         * pôde, para / pára, tem / têm, sabia / sábia, publico / público). Seuil de 10, et ces formes sans accent gardées telles quelles : le
         * contexte (PortugueseConfusions) tranche les cas sûrs.
         */
        val PORTUGUESE = AccentPolicy(
            softDominance = 10L,
            strictDominance = 10L,
            keptPlain = setOf(
                "esta", "estas", "e", "a", "nos", "vos", "por", "pode", "para", "pais", "tem", "vem", "sabia", "secretaria", "publico",
                "pratica", "critica", "da", "de", "mas", "pelo", "porque", "que", "avo",
            ),
            strictPair = { _, _ -> true },
        )

        /** Néerlandais : peu d'accents (één / een, vóór / voor, café) ; la forme sans accent est le plus souvent juste : seuil de 10. */
        val DUTCH = AccentPolicy(
            softDominance = 10L,
            strictDominance = 10L,
            keptPlain = setOf("een", "voor", "dat", "die", "is"),
            strictPair = { _, _ -> true },
        )

        /**
         * Langues ajoutées le 11/11/2026 : un seul seuil, choisi d'après les paires courantes de chaque liste (rapport forme accentuée /
         * forme sans accent). La forme sans accent d'une paire de deux mots justes reste intacte, l'oubli évident est rétabli :
         * - polonais 40 : « juz » -> « już » (54), « sie » -> « się » (59) ; « ze » / « że » (7) intacts ;
         * - tchèque 40 : « mam » -> « mám » (287) ; « ze » / « že » (19), « byt » / « být » (35) intacts ;
         * - hongrois 50 : « mar » -> « már » (771) ; « meg » / « még » (1) intact ;
         * - suédois, danois, norvégien, finnois 50 (å, ä, ö, æ, ø ont leur touche, comme les umlauts allemands) : « ar » -> « är »,
         *   « pa » -> « på », « etta » -> « että » ; « sa » / « så », « nar » / « når », « ala » / « älä » intacts.
         */
        fun uniform(dominance: Long, keptPlain: Set<String> = emptySet()) = AccentPolicy(
            softDominance = dominance,
            strictDominance = dominance,
            keptPlain = keptPlain,
            strictPair = { _, _ -> true },
        )

        val POLISH = uniform(40L)
        val CZECH = uniform(40L)
        val HUNGARIAN = uniform(50L)
        val NORDIC = uniform(50L)

        /**
         * Roumain : on tape souvent sans diacritiques (les sous-titres de la liste aussi : « si » pour « și », 4 contre 1). Seuil de 4,
         * sauf les formes sans accent qui sont aussi des mots courants : « sa » (son, sa), « ca » (comme), « cat » (étage), « tine » (toi),
         * « peste » (au-dessus), « fata » (la fille) ; l'accent y reste proposé dans la bande.
         */
        val ROMANIAN = uniform(4L, setOf("sa", "ca", "cat", "tine", "peste", "fata"))

        /** Espagnol : un seul seuil de 10, et les mots justes dont l'accent change le sens à plus de 10 contre 1. */
        val SPANISH = AccentPolicy(
            softDominance = 10L,
            strictDominance = 10L,
            keptPlain = setOf("papa", "papas", "seria", "serias", "sabia", "sabias"),
            strictPair = { _, _ -> true },
        )
    }
}
