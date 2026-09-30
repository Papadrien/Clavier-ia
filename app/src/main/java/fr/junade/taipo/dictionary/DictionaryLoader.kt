package fr.junade.taipo.dictionary

import android.content.Context
import fr.junade.taipo.KeyboardLanguage

/**
 * Charge le [Dictionary] correspondant à une [KeyboardLanguage] depuis les
 * assets de l'app (un fichier texte, une ligne « mot fréquence » — voir
 * `assets/dictionaries/fr.txt` et `en.txt`, ~50 000 mots triés par fréquence décroissante ;
 * la fréquence sert à départager les corrections, story 1.14).
 *
 * Résultat mis en cache par langue : le fichier n'est lu qu'une fois par
 * processus IME (le dictionnaire est fixe, story 1.3 — pas besoin de
 * recharger tant que l'app n'est pas mise à jour).
 */
object DictionaryLoader {

    private val cache = mutableMapOf<KeyboardLanguage, Dictionary>()

    @Synchronized
    fun forLanguage(context: Context, language: KeyboardLanguage): Dictionary {
        cache[language]?.let { return it }
        val dictionary = Dictionary.withFrequencies(
            readAsset(context, assetNameFor(language)),
            inflectionsFor(language),
        )
        cache[language] = dictionary
        return dictionary
    }

    private fun assetNameFor(language: KeyboardLanguage): String = when (language) {
        KeyboardLanguage.FR -> "dictionaries/fr.txt"
        KeyboardLanguage.EN -> "dictionaries/en.txt"
    }

    /** Formes régulières (pluriels, etc.) absentes des listes de fréquence : voir [InflectionRules]. */
    private fun inflectionsFor(language: KeyboardLanguage): InflectionRules = when (language) {
        KeyboardLanguage.FR -> InflectionRules.FRENCH
        KeyboardLanguage.EN -> InflectionRules.ENGLISH
    }

    private fun readAsset(context: Context, assetPath: String): Map<String, Long> =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines)
        }
}
