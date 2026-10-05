package fr.junade.taipo.dictionary

import android.content.Context
import fr.junade.taipo.AppLog
import fr.junade.taipo.Sections
import fr.junade.taipo.traced
import fr.junade.taipo.BuildConfig
import fr.junade.taipo.KeyboardLanguage
import java.util.concurrent.ConcurrentHashMap

/**
 * Charge le [Dictionary] correspondant à une [KeyboardLanguage] depuis les
 * assets de l'app (un fichier texte, une ligne « mot fréquence » — voir
 * `assets/dictionaries/`, triés par fréquence décroissante ; la fréquence sert à départager
 * les corrections, story 1.14). `en.txt` : ~50 000 mots. `fr.txt` : liste française complète
 * (~767 000 mots). `fr_50k.txt` : l'ancienne liste de ~50 000 mots, gardée en secours : si la liste
 * complète ne peut pas être chargée (mémoire insuffisante, fichier illisible), le chargeur
 * bascule dessus au lieu de laisser le clavier sans dictionnaire.
 *
 * Liste complète : deux seuils de fréquence ([FR_MIN_FREQUENCY], [FR_CANDIDATE_MIN_FREQUENCY], détail
 * sur leurs constantes) gardent la mémoire et la vitesse d'autocorrection proches de celles de la liste de
 * 50 000 mots. Pour revenir à cette liste sans toucher au code, remplacer [FR_ASSET] par [FR_BACKUP_ASSET].
 *
 * Résultat mis en cache par langue : le fichier n'est lu qu'une fois par
 * processus IME (le dictionnaire est fixe, story 1.3 — pas besoin de
 * recharger tant que l'app n'est pas mise à jour).
 *
 * Lot 1.4 de la revue de code : le chargement (lecture + indexation de dizaines de milliers de mots) est lent et ne
 * doit jamais se faire sur le thread principal. [forLanguage] est bloquant : il est réservé au
 * préchargement sur un thread d'arrière-plan. Le thread principal utilise [peek], qui ne bloque
 * jamais (null tant que la langue n'est pas chargée).
 */
object DictionaryLoader {

    private const val TAG = "DictionaryLoader"

    /** Liste française utilisée : la liste complète. */
    private const val FR_ASSET = "dictionaries/fr.txt"

    /** Ancienne liste de ~50 000 mots : secours si [FR_ASSET] ne se charge pas. */
    private const val FR_BACKUP_ASSET = "dictionaries/fr_50k.txt"

    /**
     * Fréquence minimale d'un mot de [FR_ASSET] pour être retenu (valide, donc jamais « corrigé »). Les mots
     * vus moins de 5 fois dans le corpus sont surtout des fautes de frappe et des débris d'OCR : avec tous les
     * mots, ~13 % des fautes de frappe aléatoires tombaient sur un « mot » valide et n'étaient plus corrigées
     * (~6 % à 5, ~2,5 % pour la liste de 50 000 mots). 1 = tous les mots du fichier.
     */
    private const val FR_MIN_FREQUENCY = 5L

    /**
     * Fréquence minimale d'un mot pour être proposé comme correction ou complétion : environ 50 000 mots,
     * comme l'ancienne liste. Les mots plus rares restent valides mais ne sont jamais suggérés.
     */
    private const val FR_CANDIDATE_MIN_FREQUENCY = 100L

    /** Fichier et seuils de fréquence d'une liste. */
    private class Source(val asset: String, val minFrequency: Long = 0L, val candidateMinFrequency: Long = 0L)

    private val cache = ConcurrentHashMap<KeyboardLanguage, Dictionary>()

    /** Dictionnaire déjà chargé pour [language], ou null. Ne bloque jamais et ne charge rien. */
    fun peek(language: KeyboardLanguage): Dictionary? = cache[language]

    /** Charge (si besoin) et renvoie le dictionnaire : bloquant, à appeler hors du thread principal. */
    fun forLanguage(context: Context, language: KeyboardLanguage): Dictionary {
        cache[language]?.let { return it }
        synchronized(this) {
            cache[language]?.let { return it }
            val startNanos = System.nanoTime()
            val dictionary = traced(Sections.DICTIONARY_LOAD) { loadWithBackup(context, language) }
            cache[language] = dictionary
            if (BuildConfig.DEBUG) {
                AppLog.d(TAG, "dictionnaire $language chargé en ${(System.nanoTime() - startNanos) / 1_000_000} ms")
            }
            return dictionary
        }
    }

    private fun sourceFor(language: KeyboardLanguage): Source = when (language) {
        KeyboardLanguage.FR -> Source(FR_ASSET, FR_MIN_FREQUENCY, FR_CANDIDATE_MIN_FREQUENCY)
        KeyboardLanguage.EN -> Source("dictionaries/en.txt")
    }

    /** Liste de secours de [language], ou null s'il n'y en a pas. */
    private fun backupSourceFor(language: KeyboardLanguage): Source? = when (language) {
        KeyboardLanguage.FR -> Source(FR_BACKUP_ASSET)
        KeyboardLanguage.EN -> null
    }

    /** Charge la liste principale ; si elle échoue (mémoire, lecture) et qu'une liste de secours existe, charge celle-ci. */
    private fun loadWithBackup(context: Context, language: KeyboardLanguage): Dictionary {
        val backup = backupSourceFor(language)
        try {
            return load(context, language, sourceFor(language))
        } catch (e: OutOfMemoryError) {
            if (backup == null) throw e
            AppLog.e(TAG, "mémoire insuffisante pour le dictionnaire $language : repli sur ${backup.asset}", e)
        } catch (e: Exception) {
            if (backup == null) throw e
            AppLog.e(TAG, "dictionnaire $language illisible : repli sur ${backup.asset}", e)
        }
        return load(context, language, checkNotNull(backup))
    }

    private fun load(context: Context, language: KeyboardLanguage, source: Source): Dictionary =
        Dictionary.withFrequencies(
            readAsset(context, source.asset, source.minFrequency),
            inflectionsFor(language),
            source.candidateMinFrequency,
        )

    /** Formes régulières (pluriels, etc.) absentes des listes de fréquence : voir [InflectionRules]. */
    private fun inflectionsFor(language: KeyboardLanguage): InflectionRules = when (language) {
        KeyboardLanguage.FR -> InflectionRules.FRENCH
        KeyboardLanguage.EN -> InflectionRules.ENGLISH
    }

    private fun readAsset(context: Context, assetPath: String, minFrequency: Long): Map<String, Long> =
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, minFrequency)
        }
}
