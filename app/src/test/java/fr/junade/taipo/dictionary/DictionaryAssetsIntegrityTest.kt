package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import java.io.File
import java.text.Normalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Lot 3.3 (T3) de la revue : intégrité des listes de mots de chaque langue du clavier (fr, en, es, de, it, pt, nl) :
 * `<code>.txt` (liste complète ; le repli mémoire de `DictionaryLoader` en tire ~50 000 mots par un seuil de fréquence).
 *
 * `Dictionary.parseFrequencyLines` est volontairement tolérant (une ligne sans fréquence valide compte
 * pour 1, une ligne vide est ignorée) : une liste abîmée par un mauvais export ou un mauvais éditeur
 * passerait donc inaperçue. Ces tests relisent le fichier brut et exigent le format strict décrit dans
 * `dictionaries/SOURCES.txt` : une ligne « mot fréquence », triée par fréquence décroissante.
 *
 * Les seuils (taille plancher, lettres isolées) reprennent l'état constaté des fichiers au 04/10/2026 :
 * ils détectent une régression, pas une perfection linguistique (les fautes de corpus connues, citées
 * dans SOURCES.txt, ne sont volontairement pas testées).
 */
class DictionaryAssetsIntegrityTest {

    private class Entry(val lineNumber: Int, val word: String, val frequency: Long)

    private fun assetFile(name: String): File {
        // Les listes ne sont plus dans l'APK : elles vivent dans dictionaries/ à la racine du projet (source de ce qui est envoyé
        // sur le bucket R2). Gradle lance les tests JVM depuis le dossier du module (app/) ; on tolère aussi la racine du projet.
        val candidates = listOf(File("../dictionaries/$name"), File("dictionaries/$name"))
        return candidates.firstOrNull { it.isFile } ?: fail("Fichier de dictionnaire introuvable : $name (cherché dans $candidates)")
    }

    private fun rawLines(language: String): List<String> {
        val bytes = assetFile("$language.txt").readBytes()
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(!text.contains('\r'), "$language.txt contient des retours chariot (CRLF) : fins de ligne attendues « \\n »")
        assertTrue(!text.contains('\uFEFF'), "$language.txt contient un BOM / U+FEFF")
        assertTrue(text.endsWith("\n"), "$language.txt doit se terminer par un saut de ligne")
        return text.removeSuffix("\n").split("\n")
    }

    /** Parse strictement : toute ligne qui n'est pas exactement « mot<espace>entier » fait échouer le test. */
    private fun strictEntries(language: String): List<Entry> =
        rawLines(language).mapIndexed { index, line ->
            val lineNumber = index + 1
            val parts = line.split(' ')
            if (parts.size != 2 || parts[0].isEmpty()) {
                fail<Nothing>("$language.txt ligne $lineNumber : format « mot fréquence » attendu, trouvé « $line »")
            }
            val frequency = parts[1].toLongOrNull()
                ?: fail("$language.txt ligne $lineNumber : fréquence non numérique dans « $line »")
            Entry(lineNumber, parts[0], frequency)
        }

    @ParameterizedTest(name = "{0}.txt : format strict, une ligne « mot fréquence »")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `chaque ligne est un mot suivi d'une frequence`(language: String) {
        // strictEntries échoue avec le numéro de ligne fautif ; ici on vérifie aussi qu'il y a du contenu.
        assertTrue(strictEntries(language).isNotEmpty())
    }

    @ParameterizedTest(name = "{0}.txt : taille plancher")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `la liste garde une taille plancher`(language: String) {
        val size = strictEntries(language).size
        // Un fichier tronqué tomberait bien en dessous (la plus petite liste, de, compte ~117 000 mots).
        assertTrue(size >= 45_000, "$language.txt : $size mots seulement (plancher : 45 000)")
    }

    @ParameterizedTest(name = "{0} : liste complète")
    @CsvSource(delimiter = '|', value = ["fr|700000", "en|120000", "es|140000", "de|100000", "it|220000", "pt|220000", "nl|220000"])
    fun `la liste complete est bien la liste complete`(language: String, minimumWords: Int) {
        // Constaté : fr 735 098 mots, en 133 339 (élaguée le 05/11/2026), es 147 805 (nettoyée le 05/11/2026), de 117 170 (nettoyée le 06/11/2026), it 236 944, pt 238 172, nl 245 627.
        val full = strictEntries(language).size
        assertTrue(full >= minimumWords, "$language.txt : $full mots seulement (plancher : $minimumWords)")
    }

    @ParameterizedTest(name = "{0}.txt : le seuil de repli mémoire garde environ 50 000 mots")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `le repli memoire garde environ cinquante mille mots`(language: String) {
        // DictionaryLoader relit la liste complète en ne gardant que les mots de fréquence >= candidateMinFrequency quand la
        // mémoire manque (il remplace les anciennes listes `_50k`). Si la liste est régénérée et que le seuil ne suit pas, le repli
        // deviendrait trop gros (inutile) ou trop petit (clavier sans dictionnaire utile).
        val threshold = DictionaryLoader.candidateMinFrequency(KeyboardLanguage.fromTag(language)!!)
        val kept = strictEntries(language).count { it.frequency >= threshold }
        assertTrue(kept in 45_000..55_000, "$language.txt : $kept mots de fréquence >= $threshold (attendu : environ 50 000)")
    }

    @ParameterizedTest(name = "{0}.txt : fréquences strictement positives")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `les frequences sont strictement positives`(language: String) {
        strictEntries(language).forEach {
            assertTrue(it.frequency > 0, "$language.txt ligne ${it.lineNumber} : fréquence ${it.frequency} pour « ${it.word} »")
        }
    }

    @ParameterizedTest(name = "{0}.txt : triée par fréquence décroissante")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `la liste est triee par frequence decroissante`(language: String) {
        // Des égalités sont possibles (fréquences identiques) ; une remontée de fréquence ne l'est pas.
        strictEntries(language).zipWithNext().forEach { (previous, next) ->
            assertTrue(
                next.frequency <= previous.frequency,
                "$language.txt ligne ${next.lineNumber} : « ${next.word} » (${next.frequency}) " +
                    "plus fréquent que « ${previous.word} » (${previous.frequency}) au-dessus",
            )
        }
    }

    @ParameterizedTest(name = "{0}.txt : pas de doublon")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `aucun mot n'apparait deux fois`(language: String) {
        val firstLineOf = HashMap<String, Int>()
        strictEntries(language).forEach { entry ->
            val previous = firstLineOf.put(entry.word, entry.lineNumber)
            assertTrue(
                previous == null,
                "$language.txt : « ${entry.word} » en double (lignes $previous et ${entry.lineNumber})",
            )
        }
    }

    @ParameterizedTest(name = "{0}.txt : mots en minuscules, NFC, lettres + apostrophe + trait d'union")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `les mots sont normalises`(language: String) {
        strictEntries(language).forEach { entry ->
            val word = entry.word
            val where = "$language.txt ligne ${entry.lineNumber} : « $word »"
            assertEquals(word.lowercase(), word, "$where n'est pas en minuscules")
            assertEquals(Normalizer.normalize(word, Normalizer.Form.NFC), word, "$where n'est pas en forme NFC")
            assertTrue(
                word.all { it.isLetter() || it == '\'' || it == '-' },
                "$where contient un caractère autre qu'une lettre, « ' » ou « - »",
            )
            assertTrue(word.first().isLetter() && word.last().isLetter(), "$where ne commence ou ne finit pas par une lettre")
        }
    }

    @ParameterizedTest(name = "{0}.txt : lettres isolées autorisées uniquement")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = [
            "fr|a,à,y", "en|a,i", "es|a,e,o,u,y", "de|a,i,o", "it|a,e,i,o,u,è", "pt|a,e,o,é,à", "nl|e,u",
        ],
    )
    fun `seules les lettres isolees legitimes sont presentes`(language: String, allowed: String) {
        // SOURCES.txt : « lettres isolées sauf les vrais mots » de chaque langue (fr : a à y ; en : a i ; it : a e i o u è...).
        val singles = strictEntries(language).map { it.word }.filter { it.length == 1 }.toSet()
        assertEquals(allowed.split(',').toSet(), singles, "$language.txt : lettres isolées inattendues")
    }

    @ParameterizedTest(name = "{0}.txt : mots courants présents")
    // quoteCharacter : l'apostrophe (j'ai, c'est, it's) est le guillemet par défaut de @CsvSource.
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            "fr|de,je,est,pas,le,que,la,vous,tu,un,être,ça,où,j'ai,c'est",
            "en|you,i,the,to,a,it,it's,and,that,don't",
            "es|de,que,no,el,la,es,yo,está,qué,señor",
            "de|ich,sie,das,ist,nicht,und,für,über,schön",
            "it|e,non,che,di,è,perché,così,città",
            "pt|que,não,de,você,está,então,é,já",
            "nl|ik,je,het,de,dat,is,niet,zijn",
        ],
    )
    fun `les mots les plus courants sont presents`(language: String, expected: String) {
        val words = strictEntries(language).map { it.word }.toSet()
        val missing = expected.split(',').filterNot { it in words }
        assertTrue(missing.isEmpty(), "$language.txt : mots courants absents : $missing")
    }

    @ParameterizedTest(name = "{0}.txt : le chargeur lit autant de mots que le fichier en contient")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `le chargeur ne perd aucune ligne`(language: String) {
        val strict = strictEntries(language)
        val parsed = Dictionary.parseFrequencyLines(rawLines(language).asSequence())
        assertEquals(strict.size, parsed.size, "$language.txt : le chargeur n'a pas retenu autant de mots que de lignes")
        // Les fréquences lues par le chargeur sont celles du fichier (pas de repli silencieux à 1).
        val sample = strict.filterIndexed { index, _ -> index % 997 == 0 }
        sample.forEach { assertEquals(it.frequency, parsed[it.word] ?: -1L, "$language.txt : fréquence de « ${it.word} »") }
    }

    @ParameterizedTest(name = "SOURCES.txt mentionne {0}.txt")
    @ValueSource(strings = ["fr", "en", "es", "de", "it", "pt", "nl"])
    fun `la provenance de chaque liste est documentee`(language: String) {
        val sources = assetFile("SOURCES.txt").readText(Charsets.UTF_8)
        assertTrue(sources.contains("$language.txt"), "SOURCES.txt ne mentionne pas $language.txt")
        assertTrue(sources.contains("CC-BY-SA"), "SOURCES.txt ne rappelle pas la licence (attribution requise)")
    }
}
