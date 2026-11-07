package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import java.io.File

/**
 * Les 2 fichiers d'une langue. Le même chemin relatif sert sur le serveur (`dictionnaries/<chemin>`) et dans le
 * stockage de l'app (`filesDir/dictionaries/<chemin>`).
 */
enum class DictionaryFile {
    /** Prénoms avec leur majuscule : `names/<code>.txt`. */
    NAMES,

    /** Liste complète de la langue : `<code>.txt`. */
    FULL,
    ;

    fun relativePath(language: KeyboardLanguage): String = when (this) {
        FULL -> "${language.tag}.txt"
        NAMES -> "names/${language.tag}.txt"
    }
}

/**
 * Emplacement local et adresse distante des dictionnaires (ils ne sont plus dans l'APK : ils se téléchargent depuis le
 * bucket R2 dès qu'une langue est utilisée). Sans dépendance Android : testé en JVM.
 */
object DictionaryFiles {

    /** Dossier local, sous `filesDir` (exclu des sauvegardes : voir `backup_rules.xml`). */
    const val DIRECTORY_NAME = "dictionaries"

    /** Même serveur (Worker Cloudflare devant R2) que les modèles : voir `VoiceModelFile` et `AiModel`. */
    const val SERVER_BASE_URL = "https://taipo-worker.junade-models.workers.dev/"

    /** Dossier du bucket R2 (orthographe du bucket : « dictionnaries »). */
    const val REMOTE_DIRECTORY = "dictionnaries"

    fun directory(filesDir: File): File = File(filesDir, DIRECTORY_NAME)

    fun localFile(filesDir: File, language: KeyboardLanguage, file: DictionaryFile): File =
        File(directory(filesDir), file.relativePath(language))

    /**
     * Ancienne liste de secours (`<code>_50k.txt`), plus utilisée : le repli en cas de mémoire insuffisante relit la liste
     * complète avec un seuil de fréquence (voir `DictionaryLoader`). Supprime le fichier laissé par une version précédente.
     * @return true si un fichier a été supprimé.
     */
    fun deleteObsoleteBackup(filesDir: File, language: KeyboardLanguage): Boolean =
        File(directory(filesDir), "${language.tag}_50k.txt").let { it.isFile && it.delete() }

    /**
     * Bigrammes de [language] (point 1 de docs/autocorrection-roadmap.md) : `bigrams/<code>.txt`, FACULTATIFS. Ils ne font pas partie
     * de [DictionaryFile] : une langue est installée sans eux, et leur absence (pas encore sur le serveur, pas de réseau) laisse
     * l'autocorrection telle qu'avant. Seules les langues de [BIGRAM_LANGUAGES] en ont.
     */
    fun bigramFile(filesDir: File, language: KeyboardLanguage): File = File(directory(filesDir), "bigrams/${language.tag}.txt")

    fun bigramRemoteUrl(language: KeyboardLanguage): String = "$SERVER_BASE_URL$REMOTE_DIRECTORY/bigrams/${language.tag}.txt"

    /** Langues dont les bigrammes existent et dont l'autocorrection s'en sert (`CorrectionConfidence.contextWeight`). */
    val BIGRAM_LANGUAGES: Set<KeyboardLanguage> = setOf(KeyboardLanguage.FR, KeyboardLanguage.EN)

    /** Vrai si [language] a des bigrammes et qu'ils ne sont pas encore là. */
    fun bigramsMissing(filesDir: File, language: KeyboardLanguage): Boolean =
        language in BIGRAM_LANGUAGES && bigramFile(filesDir, language).let { !it.isFile || it.length() == 0L }

    fun remoteUrl(language: KeyboardLanguage, file: DictionaryFile): String =
        "$SERVER_BASE_URL$REMOTE_DIRECTORY/${file.relativePath(language)}"

    /** Fichiers de [language] absents (ou vides) : un fichier n'apparaît qu'une fois complet (rename atomique). */
    fun missing(filesDir: File, language: KeyboardLanguage): List<DictionaryFile> =
        DictionaryFile.entries.filter { localFile(filesDir, language, it).let { f -> !f.isFile || f.length() == 0L } }

    fun isInstalled(filesDir: File, language: KeyboardLanguage): Boolean = missing(filesDir, language).isEmpty()

    /**
     * Taille indicative (octets), pour la progression globale seulement : jamais utilisée pour valider un fichier.
     * Relevée sur les fichiers du 16/10/2026 (fr : 04/11/2026, après enrichissement).
     */
    fun approxBytes(language: KeyboardLanguage, file: DictionaryFile): Long = when (file) {
        DictionaryFile.NAMES -> 1_200L
        DictionaryFile.FULL -> when (language) {
            KeyboardLanguage.FR -> 9_155_000L
            KeyboardLanguage.EN -> 1_606_000L
            KeyboardLanguage.ES -> 4_110_000L
            KeyboardLanguage.DE -> 3_240_000L
            KeyboardLanguage.IT -> 2_910_000L
            KeyboardLanguage.PT -> 2_960_000L
            KeyboardLanguage.NL -> 3_170_000L
        }
    }
}
