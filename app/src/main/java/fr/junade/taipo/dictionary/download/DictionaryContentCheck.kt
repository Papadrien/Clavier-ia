package fr.junade.taipo.dictionary.download

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Contrôle d'un fichier de langue reçu, avant son installation. Il remplace l'empreinte SHA-256 exacte (retirée le 13/11/2026) :
 * un fichier un peu différent de celui attendu (liste mise à jour sur le serveur) vaut mieux que pas de fichier du tout. On écarte
 * seulement ce qui n'est manifestement pas une liste de la langue : page d'erreur HTML ou JSON renvoyée avec un code 200, fichier
 * vide, tronqué, binaire ou d'une tout autre taille. Le fichier est lu en entier (quelques Mo).
 *
 * Règles :
 * - texte UTF-8 valide, qui ne commence pas par `<` ou `{` ;
 * - au moins [MIN_VALID_RATIO] des lignes utiles (ni vides, ni commentaires `#`) au format de [Kind], et au moins [Kind.minEntries] ;
 * - si une taille indicative est connue, une taille entre son tiers et son triple ([SIZE_FACTOR]) : marge pour enrichir les listes.
 */
object DictionaryContentCheck {

    /** Format attendu d'un fichier, avec le nombre minimal de lignes valides (bien en dessous des vrais fichiers). */
    enum class Kind(val line: Regex, val minEntries: Int) {
        /** Liste complète : `mot fréquence` (plus de 100 000 lignes pour chaque langue). */
        WORDS(Regex("""^\S+ \d+$"""), 10_000),

        /** Prénoms : un mot par ligne (plus de 60 lignes pour chaque langue). */
        NAMES(Regex("""^[^\s<>{}]+$"""), 20),

        /** Bigrammes : `mot fréquence` puis `mot1 mot2 fréquence` (plus de 13 000 lignes pour chaque langue). */
        BIGRAMS(Regex("""^\S+( \S+)? \d+$"""), 1_000),
    }

    const val MIN_VALID_RATIO = 0.95
    const val SIZE_FACTOR = 3

    /** Vrai si [file] ressemble à un fichier [kind] ; [approxBytes] : taille indicative, null si inconnue (pas de contrôle de taille). */
    fun looksValid(file: File, kind: Kind, approxBytes: Long? = null): Boolean {
        if (!file.isFile) return false
        if (approxBytes != null && approxBytes > 0) {
            val size = file.length()
            if (size < approxBytes / SIZE_FACTOR || size > approxBytes * SIZE_FACTOR) return false
        }
        val counts = count(file, kind) ?: return false
        return !counts.markup && counts.valid >= kind.minEntries && counts.valid >= counts.entries * MIN_VALID_RATIO
    }

    private class Counts(var entries: Long = 0, var valid: Long = 0, var markup: Boolean = false)

    /** Compte les lignes utiles et celles au format de [kind] ; null si le fichier n'est pas du texte UTF-8 (binaire, autre encodage). */
    private fun count(file: File, kind: Kind): Counts? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            InputStreamReader(file.inputStream(), decoder).buffered().useLines { lines ->
                val counts = Counts()
                lines.map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
                    // Page d'erreur HTML ou réponse JSON renvoyée avec un code 200.
                    if (index == 0 && (line.startsWith("<") || line.startsWith("{"))) counts.markup = true
                    if (!line.startsWith("#")) {
                        counts.entries++
                        if (kind.line.matches(line)) counts.valid++
                    }
                }
                counts
            }
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** @throws IOException si le fichier n'a pas la forme attendue (le téléchargement est alors rejeté). */
    fun requireValid(file: File, kind: Kind, approxBytes: Long? = null) {
        if (!looksValid(file, kind, approxBytes)) throw IOException("Contenu inattendu pour $kind")
    }
}
