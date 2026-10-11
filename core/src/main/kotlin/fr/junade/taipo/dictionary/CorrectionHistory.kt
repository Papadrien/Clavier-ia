package fr.junade.taipo.dictionary

import fr.junade.taipo.suggestion.WordText

/**
 * Mémoire des mots corrigés par l'autocorrection pendant une saisie : mot corrigé (minuscules) -> mot tel que tapé.
 * Sert à proposer, curseur posé sur un mot corrigé, de revenir à sa forme d'origine (emplacement de gauche de la
 * bande de suggestions). Bornée aux [capacity] corrections les plus récentes. Logique pure (sans Android).
 */
class CorrectionHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    private val forms = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > capacity
    }

    /**
     * Retient qu'[original] (tel que tapé) a été remplacé par [corrected]. Les deux textes sont comparés mot à mot :
     * chaque mot modifié est retenu (une correction de contexte peut en couvrir plusieurs). Sans effet si les textes
     * n'ont pas le même nombre de mots. Un changement de casse seul n'est pas une correction.
     */
    fun record(original: String, corrected: String) {
        val before = original.trim().split(WHITESPACE)
        val after = corrected.trim().split(WHITESPACE)
        if (before.size != after.size) return
        for (index in before.indices) {
            val was = before[index].trim { !WordText.isWordChar(it) }
            val now = after[index].trim { !WordText.isWordChar(it) }
            if (was.isEmpty() || now.isEmpty() || was.equals(now, ignoreCase = true)) continue
            forms.remove(now.lowercase()) // le plus récent passe en dernier
            forms[now.lowercase()] = was
        }
    }

    /** Le mot tel qu'il avait été tapé avant d'être corrigé en [word] (insensible à la casse), ou null. */
    fun originalOf(word: String): String? = forms[word.lowercase()]

    fun clear() = forms.clear()

    private companion object {
        const val DEFAULT_CAPACITY = 64
        val WHITESPACE = Regex("\\s+")
    }
}
