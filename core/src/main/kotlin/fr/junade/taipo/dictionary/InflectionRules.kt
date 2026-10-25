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
 * Formes régulières reconnues en plus des mots du dictionnaire. Les listes de fréquence sources
 * (~50 000 mots) ne contiennent pas toutes les formes fléchies (ex. « durée » y est, pas
 * « durées ») : sans ces règles, un mot correct est « corrigé » vers un voisin (« durées » →
 * « durée », « durees » → « dures »). Les formes ne sont pas générées en mémoire : on remonte de
 * la forme tapée vers le mot de base et on vérifie qu'il est connu.
 *
 * Volontairement limité aux flexions les plus régulières : les conjugaisons irrégulières ne sont
 * pas couvertes (seules celles présentes dans les listes le sont).
 */
class InflectionRules(val rules: List<InflectionRule>) {

    companion object {
        val NONE = InflectionRules(emptyList())

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
        )
    }
}
