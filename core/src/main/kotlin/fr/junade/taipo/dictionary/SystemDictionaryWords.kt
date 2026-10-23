package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage

/**
 * Mots du dictionnaire utilisateur du système Android (ceux que la personne enregistre en appuyant sur un mot
 * souligné en rouge, puis « Ajouter au dictionnaire »), préparés pour l'autocorrection de Taipo.
 *
 * Lecture seule : Taipo ne modifie jamais ce dictionnaire. Ces mots sont traités comme les mots du dictionnaire
 * personnel de Taipo : jamais corrigés, et proposés comme candidats de correction. Ils ne sont ni copiés dans la base
 * de Taipo, ni sauvegardés, ni exportés, ni envoyés à la mesure d'usage.
 *
 * Objet pur (aucune dépendance Android) : la lecture du `ContentProvider` est dans le module app
 * (`SystemUserDictionary`), pour rester testable en JVM simple.
 */
object SystemDictionaryWords {

    /** Plafond : la recherche de correction parcourt ces mots à chaque fin de mot (comme pour [PersonalDictionary.MAX_WORDS]). */
    const val MAX_WORDS = 2000

    /** Une ligne du dictionnaire système : [locale] vaut null ou vide pour un mot valable dans toutes les langues. */
    data class Entry(val word: String, val frequency: Int, val locale: String?)

    /**
     * Mots à utiliser quand le clavier est en [language] : mots valables pour toutes les langues, et mots de cette
     * langue (« fr_FR » et « fr » comptent pour le français). Mots invalides pour l'autocorrection écartés
     * (voir [PersonalDictionary.normalize]), doublons retirés sans tenir compte de la casse, les plus fréquents d'abord,
     * au plus [MAX_WORDS].
     */
    fun select(entries: Collection<Entry>, language: KeyboardLanguage): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        entries.asSequence()
            .filter { matches(it.locale, language) }
            .sortedByDescending { it.frequency }
            .forEach { entry ->
                if (result.size >= MAX_WORDS) return@forEach
                val word = PersonalDictionary.normalize(entry.word) ?: return@forEach
                if (seen.add(PersonalDictionary.keyOf(word))) result += word
            }
        return result
    }

    private fun matches(locale: String?, language: KeyboardLanguage): Boolean {
        if (locale.isNullOrBlank()) return true
        return KeyboardLanguage.fromTag(locale) == language
    }

    /**
     * Mots de [personal] suivis des mots de [system] qui n'y figurent pas (comparaison insensible à la casse : la
     * graphie du dictionnaire personnel de Taipo prime).
     */
    fun merge(personal: List<String>, system: List<String>): List<String> {
        if (system.isEmpty()) return personal
        if (personal.isEmpty()) return system
        val known = HashSet<String>(personal.size * 2)
        personal.forEach { known += PersonalDictionary.keyOf(it) }
        val result = ArrayList<String>(personal.size + system.size)
        result.addAll(personal)
        system.forEach { if (known.add(PersonalDictionary.keyOf(it))) result += it }
        return result
    }

    /**
     * Fusion mémorisée : [merge] est appelé à chaque frappe, alors que les deux listes changent rarement. Tant que
     * les deux listes sont les mêmes instances, le résultat précédent est renvoyé.
     */
    class MergeCache {
        private var personal: List<String>? = null
        private var system: List<String>? = null
        private var merged: List<String> = emptyList()

        @Synchronized
        fun merge(personal: List<String>, system: List<String>): List<String> {
            if (personal !== this.personal || system !== this.system) {
                merged = SystemDictionaryWords.merge(personal, system)
                this.personal = personal
                this.system = system
            }
            return merged
        }
    }
}
