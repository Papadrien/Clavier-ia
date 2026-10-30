package fr.junade.taipo.dictionary

import android.content.Context
import fr.junade.taipo.AppLog
import fr.junade.taipo.KeyboardPreferences
import fr.junade.taipo.Sections
import fr.junade.taipo.traced
import fr.junade.taipo.KeyboardLanguage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Charge le [Dictionary] correspondant à une [KeyboardLanguage] depuis les fichiers téléchargés
 * (`filesDir/dictionaries/`, voir [DictionaryFiles] : ils ne sont plus dans l'APK, le clavier les télécharge dès qu'une
 * langue est utilisée). Un fichier texte, une ligne « mot fréquence », triés par fréquence décroissante ; la fréquence
 * sert à départager les corrections, story 1.14). `<code>.txt` : liste COMPLÈTE de la langue (fr, en, es, de, it, pt, nl).
 * `<code>_50k.txt` : liste de ~50 000 mots gardée en secours : si la liste complète ne peut pas être chargée
 * (mémoire insuffisante, fichier illisible), le chargeur bascule dessus au lieu de laisser le clavier sans dictionnaire.
 *
 * Listes complètes : deux seuils de fréquence ([MIN_FREQUENCY], [candidateMinFrequency], détail sur leurs
 * constantes) gardent la mémoire et la vitesse d'autocorrection proches de celles d'une liste de 50 000 mots.
 *
 * Résultat mis en cache par langue : le fichier n'est lu qu'une fois par processus IME. Une langue dont les fichiers ne sont
 * pas (encore) téléchargés n'est PAS mise en cache : le chargement reprend dès qu'ils sont là.
 *
 * Lot 1.4 de la revue de code : le chargement (lecture + indexation de dizaines de milliers de mots) est lent et ne
 * doit jamais se faire sur le thread principal. [forLanguage] est bloquant : il est réservé au
 * préchargement sur un thread d'arrière-plan. Le thread principal utilise [peek], qui ne bloque
 * jamais (null tant que la langue n'est pas chargée).
 */
object DictionaryLoader {

    private const val TAG = "DictionaryLoader"

    /**
     * Fréquence minimale d'un mot pour être retenu (valide, donc jamais « corrigé »). Les mots vus moins de 5 fois
     * dans le corpus sont surtout des fautes de frappe et des débris d'OCR : avec tous les mots, ~13 % des fautes
     * de frappe aléatoires tombaient sur un « mot » valide et n'étaient plus corrigées (~6 % à 5, ~2,5 % pour la
     * liste de 50 000 mots ; mesuré sur le français). 1 = tous les mots du fichier.
     */
    private const val MIN_FREQUENCY = 5L

    /**
     * Fréquence minimale d'un mot pour être proposé comme correction ou complétion : environ 50 000 mots par
     * langue (la fréquence du 50 000e mot de chaque liste, les corpus n'ayant pas la même taille). Les mots plus
     * rares restent valides mais ne sont jamais suggérés.
     */
    private fun candidateMinFrequency(language: KeyboardLanguage): Long = when (language) {
        KeyboardLanguage.FR -> 100L
        KeyboardLanguage.EN -> 149L
        KeyboardLanguage.ES -> 183L
        KeyboardLanguage.DE -> 61L
        KeyboardLanguage.IT -> 102L
        KeyboardLanguage.PT -> 91L
        KeyboardLanguage.NL -> 77L
    }

    /** Fichier et seuils de fréquence d'une liste. */
    private class Source(val file: File, val minFrequency: Long = 0L, val candidateMinFrequency: Long = 0L)

    private val cache = ConcurrentHashMap<KeyboardLanguage, Dictionary>()

    /** Dictionnaire déjà chargé pour [language], ou null. Ne bloque jamais et ne charge rien. */
    fun peek(language: KeyboardLanguage): Dictionary? = cache[language]

    /**
     * Oublie le dictionnaire chargé de [language] : il est relu au prochain préchargement (ouverture d'un champ). Appelé
     * quand la disposition de la langue change dans les paramètres, car la proximité des touches en dépend.
     */
    fun evict(language: KeyboardLanguage) {
        cache.remove(language)
    }

    /** Charge (si besoin) et renvoie le dictionnaire : bloquant, à appeler hors du thread principal. */
    fun forLanguage(context: Context, language: KeyboardLanguage): Dictionary {
        cache[language]?.let { return it }
        synchronized(this) {
            cache[language]?.let { return it }
            // Fichiers pas (encore) téléchargés : on tape sans autocorrection ni suggestions (dictionnaire vide), sans le
            // mettre en cache.
            if (!DictionaryFiles.isInstalled(context.filesDir, language)) return emptyDictionary(context, language)
            val dictionary = traced(Sections.DICTIONARY_LOAD) { loadWithBackup(context, language) }
            cache[language] = dictionary
            return dictionary
        }
    }

    /** Liste complète de [language] : `<code>.txt`. */
    private fun sourceFor(context: Context, language: KeyboardLanguage): Source =
        Source(DictionaryFiles.localFile(context.filesDir, language, DictionaryFile.FULL), MIN_FREQUENCY, candidateMinFrequency(language))

    /** Liste de secours de [language] (`<code>_50k.txt`). */
    private fun backupSourceFor(context: Context, language: KeyboardLanguage): Source =
        Source(DictionaryFiles.localFile(context.filesDir, language, DictionaryFile.BACKUP))

    private fun emptyDictionary(context: Context, language: KeyboardLanguage): Dictionary {
        AppLog.w(TAG, "dictionnaire $language pas encore téléchargé : saisie sans autocorrection ni suggestions")
        return Dictionary.withFrequencies(emptyMap(), inflectionsFor(language), 0L, proximityOf(context, language))
    }

    /** Proximité des touches de la disposition choisie pour [language] dans les paramètres. */
    private fun proximityOf(context: Context, language: KeyboardLanguage): KeyProximity =
        KeyProximityFactory.forLanguage(language, KeyboardPreferences(context).letterArrangementOf(language))

    /** Charge la liste principale ; si elle échoue (mémoire, lecture) et qu'une liste de secours existe, charge celle-ci. */
    private fun loadWithBackup(context: Context, language: KeyboardLanguage): Dictionary {
        val backup = backupSourceFor(context, language).takeIf { it.file.isFile }
        try {
            return load(context, language, sourceFor(context, language))
        } catch (e: OutOfMemoryError) {
            if (backup == null) throw e
            AppLog.e(TAG, "mémoire insuffisante pour le dictionnaire $language : repli sur ${backup.file.name}", e)
        } catch (e: Exception) {
            if (backup == null) throw e
            AppLog.e(TAG, "dictionnaire $language illisible : repli sur ${backup.file.name}", e)
        }
        return load(context, language, checkNotNull(backup))
    }

    private fun load(context: Context, language: KeyboardLanguage, source: Source): Dictionary =
        Dictionary.withFrequencies(
            readFile(source.file, source.minFrequency),
            inflectionsFor(language),
            source.candidateMinFrequency,
            // Proximité des touches de la disposition de la langue : départage les corrections à distance égale.
            proximityOf(context, language),
            // Prénoms courants avec leur majuscule (les listes de fréquence sont toutes en minuscules).
            properNames = readNames(context, language),
        )

    /** Prénoms de [language] : `names/<code>.txt`, un par ligne, « # » = commentaire. Absent : aucun nom. */
    private fun readNames(context: Context, language: KeyboardLanguage): List<String> {
        val file = DictionaryFiles.localFile(context.filesDir, language, DictionaryFile.NAMES)
        if (!file.isFile) return emptyList()
        return file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
    }

    /** Formes régulières (pluriels, etc.) absentes des listes de fréquence : voir [InflectionRules]. */
    private fun inflectionsFor(language: KeyboardLanguage): InflectionRules = when (language) {
        KeyboardLanguage.FR -> InflectionRules.FRENCH
        KeyboardLanguage.EN -> InflectionRules.ENGLISH
        // Pluriel en « s » (espagnol, portugais) : la seule flexion assez régulière pour ne pas fausser les corrections.
        KeyboardLanguage.ES, KeyboardLanguage.PT -> InflectionRules(listOf(InflectionRule("s")))
        KeyboardLanguage.DE, KeyboardLanguage.IT, KeyboardLanguage.NL -> InflectionRules.NONE
    }

    private fun readFile(file: File, minFrequency: Long): Map<String, Long> =
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            Dictionary.parseFrequencyLines(lines, minFrequency)
        }
}
