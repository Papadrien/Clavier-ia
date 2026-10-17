package fr.junade.taipo.dictionary.download

import fr.junade.taipo.dictionary.DictionaryFile
import java.io.File
import java.io.IOException

/**
 * Contrôle de forme d'un dictionnaire reçu, avant son installation : une page d'erreur HTML renvoyée avec un code 200
 * (Worker mal configuré) ne doit pas être prise pour une liste de mots. Seules les premières lignes sont lues.
 */
object DictionaryContentCheck {

    private const val LINES_CHECKED = 20
    private val FREQUENCY_LINE = Regex("""^\S+ \d+$""")

    fun looksValid(file: File, kind: DictionaryFile): Boolean {
        val lines = file.bufferedReader(Charsets.UTF_8).useLines { all ->
            all.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(LINES_CHECKED).toList()
        }
        if (lines.isEmpty()) return false
        return when (kind) {
            DictionaryFile.FULL, DictionaryFile.BACKUP -> lines.all { FREQUENCY_LINE.matches(it) }
            DictionaryFile.NAMES -> lines.none { '<' in it || '{' in it || ' ' in it }
        }
    }

    /** @throws IOException si la forme du fichier n'est pas celle attendue (le téléchargement est alors rejeté). */
    fun requireValid(file: File, kind: DictionaryFile) {
        if (!looksValid(file, kind)) throw IOException("Contenu inattendu pour $kind")
    }
}
