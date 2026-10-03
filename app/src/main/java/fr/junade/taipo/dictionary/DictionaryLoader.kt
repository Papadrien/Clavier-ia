package fr.junade.taipo.dictionary

import android.content.Context
import fr.junade.taipo.AppLog
import fr.junade.taipo.BuildConfig
import fr.junade.taipo.KeyboardLanguage
import java.util.concurrent.ConcurrentHashMap

/**
 * Charge le [Dictionary] correspondant à une [KeyboardLanguage] depuis les
 * assets de l'app (un fichier texte, une ligne « mot fréquence » — voir
 * `assets/dictionaries/fr.txt` et `en.txt`, ~50 000 mots triés par fréquence décroissante ;
 * la fréquence sert à départager les corrections, story 1.14).
 *
 * Résultat mis en cache par langue : le fichier n'est lu qu'une fois par
 * processus IME (le dictionnaire est fixe, story 1.3 — pas besoin de
 * recharger tant que l'app n'est pas mise à jour).
 *
 * Lot 1.4 de la revue de code : le chargement (lecture + indexation de ~50 000 mots) est lent et ne
 * doit jamais se faire sur le thread principal. [forLanguage] est bloquant : il est réservé au
 * préchargement sur un thread d'arrière-plan. Le thread principal utilise [peek], qui ne bloque
 * jamais (null tant que la langue n'est pas chargée).
 */
object DictionaryLoader {

    private const val TAG = "DictionaryLoader"

    private val cache = ConcurrentHashMap<KeyboardLanguage, Dictionary>()

    /** Dictionnaire déjà chargé pour [language], ou null. Ne bloque jamais et ne charge rien. */
    fun peek(language: KeyboardLanguage): Dictionary? = cache[language]

    /** Charge (si besoin) et renvoie le dictionnaire : bloquant, à appeler hors du thread principal. */
    fun forLanguage(context: Context, language: KeyboardLanguage): Dictionary {
        cache[language]?.let { return it }
        synchronized(this) {
            cache[language]?.let { return it }
            val startNanos = System.nanoTime()
            val dictionary = Dictionary.withFrequencies(
                readAsset(context, assetNameFor(language)),
                inflectionsFor(language),
            )
            cache[language] = dictionary
            if (BuildConfig.DEBUG) {
                AppLog.d(TAG, "dictionnaire $language chargé en ${(System.nanoTime() - startNanos) / 1_000_000} ms")
            }
            return dictionary
        }
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
