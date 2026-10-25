package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import java.io.File

/**
 * Les 3 fichiers d'une langue. Le même chemin relatif sert sur le serveur (`dictionnaries/<chemin>`) et dans le
 * stockage de l'app (`filesDir/dictionaries/<chemin>`).
 */
enum class DictionaryFile {
    /** Prénoms avec leur majuscule : `names/<code>.txt`. */
    NAMES,

    /** Liste de secours d'environ 50 000 mots : `<code>_50k.txt`. */
    BACKUP,

    /** Liste complète de la langue : `<code>.txt`. */
    FULL,
    ;

    fun relativePath(language: KeyboardLanguage): String = when (this) {
        FULL -> "${language.tag}.txt"
        BACKUP -> "${language.tag}_50k.txt"
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

    fun remoteUrl(language: KeyboardLanguage, file: DictionaryFile): String =
        "$SERVER_BASE_URL$REMOTE_DIRECTORY/${file.relativePath(language)}"

    /** Fichiers de [language] absents (ou vides) : un fichier n'apparaît qu'une fois complet (rename atomique). */
    fun missing(filesDir: File, language: KeyboardLanguage): List<DictionaryFile> =
        DictionaryFile.entries.filter { localFile(filesDir, language, it).let { f -> !f.isFile || f.length() == 0L } }

    fun isInstalled(filesDir: File, language: KeyboardLanguage): Boolean = missing(filesDir, language).isEmpty()

    /**
     * Taille indicative (octets), pour la progression globale seulement : jamais utilisée pour valider un fichier.
     * Relevée sur les fichiers du 16/10/2026.
     */
    fun approxBytes(language: KeyboardLanguage, file: DictionaryFile): Long = when (file) {
        DictionaryFile.NAMES -> 1_200L
        DictionaryFile.BACKUP -> 650_000L
        DictionaryFile.FULL -> when (language) {
            KeyboardLanguage.FR -> 9_070_000L
            KeyboardLanguage.EN -> 3_900_000L
            KeyboardLanguage.ES -> 4_110_000L
            KeyboardLanguage.DE -> 3_240_000L
            KeyboardLanguage.IT -> 2_910_000L
            KeyboardLanguage.PT -> 2_960_000L
            KeyboardLanguage.NL -> 3_170_000L
        }
    }
}
