package fr.junade.taipo.dictionary

/**
 * Règle de flexion régulière : forme = radical + [suffix] ; mot de base = radical + [baseEnding]
 * (ex. « chevaux » = « cheval » sans « al » + « aux » : `InflectionRule("aux", "al")`).
 *
 * Logique pure (sans dépendance Android), testable en JVM.
 */
data class InflectionRule(val suffix: String, val baseEnding: String = "") {

    /** Mot de base probable de [form], ou null si la règle ne s'applique pas. */
    fun baseOf(form: String): String? {
        if (!form.endsWith(suffix)) return null
        val base = form.substring(0, form.length - suffix.length) + baseEnding
        return if (base.length >= MIN_BASE_LENGTH) base else null
    }

    /** Forme fléchie de [base], ou null si la règle ne s'applique pas. */
    fun formOf(base: String): String? {
        if (!base.endsWith(baseEnding)) return null
        return base.substring(0, base.length - baseEnding.length) + suffix
    }

    private companion object {
        /** Un mot de base plus court est trop ambigu (« as » ne vient pas de « a »). */
        const val MIN_BASE_LENGTH = 3
    }
}

/**
 * Mots composés (allemand) : un mot absent des listes est juste s'il se découpe en parties connues, séparées au besoin par une
 * liaison (« Arbeit » + « s » + « zimmer »). Les listes de fréquence ne contiennent qu'une petite part des composés (le banc mesure
 * 24 % de composés justes remplacés à tort sans cette règle) et le français ou l'anglais n'en ont pas besoin.
 *
 * Paramètres réglés sur le banc (`tools/autocorrect-bench/de`, portage Python, voir docs/autocorrection-bench.md), pas à l'intuition :
 * parties d'au moins [minPartLength] lettres, connues avec la fréquence minimale des candidats du dictionnaire ; un composé à une
 * faute de frappe (distance 1) d'un mot fréquent n'est jamais accepté : c'est le garde-fou qui ramène les fausses acceptations de 4,9 %
 * (découpeur seul) à 0,1 % (fautes sur mots courants) et 1,2 % (fautes sur mots variés).
 */
class CompoundRules(
    val minPartLength: Int = 4,
    val links: List<String> = listOf("", "s", "n", "en", "e", "er", "es"),
) {
    companion object {
        val GERMAN = CompoundRules()
    }
}

/**
 * Formes régulières reconnues en plus des mots du dictionnaire. Les listes de fréquence sources
 * (~50 000 mots) ne contiennent pas toutes les formes fléchies (ex. « durée » y est, pas
 * « durées ») : sans ces règles, un mot correct est « corrigé » vers un voisin (« durées » →
 * « durée », « durees » → « dures »). Les formes ne sont pas générées en mémoire : on remonte de
 * la forme tapée vers le mot de base et on vérifie qu'il est connu.
 *
 * Volontairement limité aux flexions les plus régulières : les conjugaisons irrégulières ne sont
 * pas couvertes (seules celles présentes dans les listes le sont).
 */
class InflectionRules(
    val rules: List<InflectionRule>,
    /**
     * Fréquence minimale du mot de base pour valider sa forme fléchie. 0 : tout mot connu. Un mot de base rare ou douteux
     * (« rea » pour « realy ») ne doit pas valider une faute de frappe : voir [ENGLISH].
     */
    val minBaseFrequency: Long = 0L,
    /**
     * Anglais : « occured » n'est pas la forme régulière de « occur » + « ed » quand « occurred » (consonne finale doublée
     * après une seule voyelle) existe : c'est une faute, corrigée au lieu d'être validée.
     */
    val checksDoubledConsonant: Boolean = false,
    /** Allemand : mots composés valides sans être dans les listes ; null : pas de composés. */
    val compounds: CompoundRules? = null,
) {

    companion object {
        val NONE = InflectionRules(emptyList())

        /** Portugais : pluriel en « s », la seule flexion assez régulière pour ne pas fausser les corrections (comme avant). */
        val PORTUGUESE = InflectionRules(listOf(InflectionRule("s")))

        /**
         * Néerlandais : pluriel ou possessif en « 's » après une voyelle (« foto's », « Maria's ») et mots composés (« fietsenstalling »),
         * comme l'allemand, avec les liaisons néerlandaises (« s », « en », « e »).
         */
        val DUTCH = InflectionRules(
            listOf(InflectionRule("'s")),
            compounds = CompoundRules(links = listOf("", "s", "en", "e")),
        )

        /** Allemand : pas de flexion régulière (les formes fléchies sont dans les listes), mais des composés. */
        val GERMAN = InflectionRules(emptyList(), compounds = CompoundRules.GERMAN)

        /**
         * Pluriels (s, x, aux). Pas de règle « es » : « durees » s'y lirait comme « dure » + « es »
         * (mot valide) et ne serait plus corrigé en « durées ».
         */
        val FRENCH = InflectionRules(
            listOf(
                InflectionRule("s"),
                InflectionRule("x"),
                InflectionRule("aux", "al"),
            ),
        )

        /** Pluriels, 3e personne, passé, participe présent, adverbes en -ly. */
        val ENGLISH = InflectionRules(
            listOf(
                InflectionRule("s"),
                InflectionRule("es"),
                InflectionRule("ies", "y"),
                InflectionRule("ed"),
                InflectionRule("d"),
                InflectionRule("ing"),
                InflectionRule("ing", "e"),
                InflectionRule("ly"),
            ),
            minBaseFrequency = 1_000L,
            checksDoubledConsonant = true,
        )

        /**
         * Pluriels en « s » (casa / casas) et en « ces » (luz / luces). Pas de règle « es » : « casaes » s'y lirait comme « casa » + « es »
         * et ne serait plus corrigé. Les pluriels en « es » (ciudades, papeles) et les conjugaisons sont dans la liste (lexiques de clavier).
         */
        val SPANISH = InflectionRules(
            listOf(
                InflectionRule("s"),
                InflectionRule("ces", "z"),
            ),
        )
    }
}
