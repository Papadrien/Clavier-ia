package fr.junade.taipo.dictionary

import android.content.Context
import fr.junade.taipo.KeyboardLanguage

/**
 * Charge le [Dictionary] correspondant à une [KeyboardLanguage] depuis les
 * assets de l'app (un fichier texte, un mot par ligne — voir
 * `assets/dictionaries/fr.txt` et `en.txt`).
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
        val words = readAsset(context, assetNameFor(language))
        val dictionary = Dictionary(words)
        cache[language] = dictionary
        return dictionary
    }

    private fun assetNameFor(language: KeyboardLanguage): String = when (language) {
        KeyboardLanguage.FR -> "dictionaries/fr.txt"
        KeyboardLanguage.EN -> "dictionaries/en.txt"
    }

    private fun readAsset(context: Context, assetPath: String): List<String> =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
}
