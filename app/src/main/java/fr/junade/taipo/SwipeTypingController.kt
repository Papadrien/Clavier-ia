package fr.junade.taipo

import android.view.inputmethod.InputConnection
import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.dictionary.DictionaryLoader
import fr.junade.taipo.dictionary.GestureDecoder
import fr.junade.taipo.dictionary.GestureText
import fr.junade.taipo.dictionary.WordSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saisie par glissement (19/11/2026), sur le modèle de Gboard et SwiftKey :
 * - au relâchement d'un glissement ([KeyboardView.OnGestureListener]), le tracé est décodé ([GestureDecoder]) avec le
 *   dictionnaire de la langue active et les mots personnels ; le meilleur mot est écrit, précédé d'une espace si besoin
 *   ([GestureText.needsSpaceBefore]) et sans espace après ;
 * - la bande de suggestions montre les autres mots possibles à gauche et à droite du mot écrit (au centre) ; en toucher un le
 *   remplace ;
 * - ⌫ juste après efface le mot glissé entier (et l'espace ajoutée avant lui) ;
 * - une lettre tapée juste après commence un nouveau mot : une espace est insérée avant elle ; une ponctuation s'accole.
 *
 * Le décodeur est préparé hors du thread principal à l'ouverture d'un champ ([prepare]) ; un tracé se décode en quelques
 * millisecondes sur le thread principal. Toutes les méthodes sont à appeler depuis le thread principal.
 */
internal class SwipeTypingController(private val scope: CoroutineScope, private val host: Host) {

    interface Host {
        fun inputConnection(): InputConnection?
        fun language(): KeyboardLanguage
        fun personalWords(): List<String>
        fun isShifted(): Boolean
        fun isCapsLock(): Boolean

        /** Un glissement ne peut rien écrire maintenant (mode prompt, dictée, correction IA, sélection...). */
        fun blocked(): Boolean

        /** Mots à afficher dans la bande tant que le texte avant le curseur se termine par [suffix] (voir SuggestionController). */
        fun pinSuggestions(suffix: String, words: List<WordSuggestion?>)

        fun unpinSuggestions()

        /** Le mot glissé [inserted] (espace avant comprise) vient d'être écrit : retour haptique, annulation, apprentissage, barre. */
        fun onWordCommitted(inserted: String)

        /** Le mot glissé vient d'être effacé par ⌫ : barre et majuscule resynchronisées. */
        fun onWordErased()

        /** [word] a été bloqué par l'utilisateur (« Retirer du dictionnaire ») : il n'est jamais écrit par un glissement. */
        fun isBlocked(word: String): Boolean
    }

    /** Dernier mot glissé, tant qu'aucune autre touche n'a été frappée : ⌫ l'efface entier, une lettre ajoute une espace. */
    private var last: String? = null

    private var decoder: GestureDecoder? = null
    private var decoderDictionary: Dictionary? = null
    private var decoderKeys: Set<Char> = emptySet()
    private var prepareJob: Job? = null

    /** Ouverture d'un champ : prépare le décodeur des touches [keyChars] pour la langue active, hors du thread principal. */
    fun prepare(keyChars: Set<Char>) {
        last = null
        val dictionary = DictionaryLoader.peek(host.language()) ?: return
        if (dictionary === decoderDictionary && keyChars == decoderKeys) return
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val built = withContext(Dispatchers.Default) { GestureDecoder.forDictionary(dictionary, keyChars) }
            decoder = built
            decoderDictionary = dictionary
            decoderKeys = keyChars
        }
    }

    /** Glissement terminé : écrit le mot le plus probable et propose les autres dans la bande. */
    fun onGesture(path: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>) {
        if (host.blocked()) return
        val ic = host.inputConnection() ?: return
        val dictionary = DictionaryLoader.peek(host.language()) ?: return
        val candidates = decoderFor(dictionary, keys.keys).decode(path, keys, host.personalWords(), CANDIDATES + BLOCKED_MARGIN)
            .filterNot { host.isBlocked(it.word) }
            .take(CANDIDATES)
        if (candidates.isEmpty()) return
        val shifted = host.isShifted()
        val capsLock = host.isCapsLock()
        val words = candidates.map { GestureText.applyCase(it.word, shifted, capsLock) }.distinct()
        val before = ic.getTextBeforeCursor(LOOKBEHIND, 0)?.toString().orEmpty()
        val inserted = (if (GestureText.needsSpaceBefore(before)) " " else "") + words.first()
        ic.commitText(inserted, 1)
        last = inserted
        // Bande : le mot écrit au centre, les autres possibilités de part et d'autre (comme la bande d'une autocorrection).
        val strip = listOf(words.getOrNull(1), words.first(), words.getOrNull(2)).map { word ->
            word?.let { WordSuggestion(it, WordSuggestion.Kind.COMPLETION) }
        }
        host.pinSuggestions(words.first(), strip)
        host.onWordCommitted(inserted)
    }

    /** ⌫ juste après un mot glissé : efface le mot entier (et l'espace ajoutée avant lui). Faux si ce n'est pas le cas. */
    fun onBackspace(): Boolean {
        val word = last ?: return false
        last = null
        if (host.blocked()) return false
        val ic = host.inputConnection() ?: return false
        if (ic.getTextBeforeCursor(word.length, 0)?.toString() != word) return false
        ic.deleteSurroundingText(word.length, 0)
        host.unpinSuggestions()
        host.onWordErased()
        return true
    }

    /**
     * Touche frappée (autre que ⌫ juste après un mot glissé) : une lettre ou un chiffre tapé juste après le mot glissé commence
     * un nouveau mot, une espace est insérée avant lui. Le mot glissé n'est plus « le dernier ».
     */
    fun beforeKey(key: Key) {
        val word = last ?: return
        clear()
        val char = (key.action as? KeyAction.TypeChar)?.char ?: return
        if (!GestureText.startsNewWord(char)) return
        val ic = host.inputConnection() ?: return
        if (ic.getTextBeforeCursor(word.length, 0)?.toString() == word) ic.commitText(" ", 1)
    }

    /** Le mot glissé n'est plus le dernier geste (suggestion touchée, curseur déplacé, autre champ). */
    fun clear() {
        if (last == null) return
        last = null
        host.unpinSuggestions()
    }

    private fun decoderFor(dictionary: Dictionary, keyChars: Set<Char>): GestureDecoder {
        val ready = decoder
        if (ready != null && dictionary === decoderDictionary && keyChars == decoderKeys) return ready
        // Pas encore prêt (premier geste juste après l'ouverture du champ, langue changée) : construit tout de suite.
        prepareJob?.cancel()
        return GestureDecoder.forDictionary(dictionary, keyChars).also {
            decoder = it
            decoderDictionary = dictionary
            decoderKeys = keyChars
        }
    }

    private companion object {
        /** Mots demandés au décodeur : le mot écrit et deux autres pour la bande (un de plus si deux ne diffèrent que par la casse). */
        const val CANDIDATES = 4

        /** Candidats en plus demandés au décodeur, pour en garder assez une fois les mots bloqués écartés. */
        const val BLOCKED_MARGIN = 3
        const val LOOKBEHIND = 64
    }
}
