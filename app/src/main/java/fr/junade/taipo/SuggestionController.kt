package fr.junade.taipo

import android.content.Context
import android.view.inputmethod.InputConnection
import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.dictionary.DictionaryLoader
import fr.junade.taipo.dictionary.FrenchContextCorrector
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.suggestion.EmojiSuggesterLoader
import fr.junade.taipo.suggestion.NextWordModel
import fr.junade.taipo.suggestion.NextWordProvider
import fr.junade.taipo.suggestion.WordText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nombre de caractères avant le curseur récupérés pour la majuscule automatique (1.2) et le dictionnaire local (1.3). */
internal const val TEXT_CONTEXT_LOOKBEHIND = 50

/**
 * Correction à appliquer à la fin du texte avant le curseur : supprimer [deleteCount] caractères avant le curseur,
 * puis insérer [insert]. Peut couvrir plus que le mot qui vient d'être tapé (le mot précédent est revu à rebours).
 */
class AutocorrectionEdit(val deleteCount: Int, val insert: String)

/**
 * Bande de suggestions (stories 1.16 et 1.17) : mots du dictionnaire pendant la frappe d'un mot, mots
 * probables après une espace (prédiction apprise sur l'appareil), emoji suggéré ; apprentissage du mot
 * suivant ; préchargement des dictionnaires (lot 1.4) et correction du dictionnaire pour l'autocorrection.
 * Extrait de `TaipoIme` au lot 2.3 de la revue de code, sans changement de comportement.
 *
 * Reste dans l'IME : le routage des touches (champ et mode prompt), les touches sur un mot ou un emoji
 * suggéré, l'application et l'annulation de l'autocorrection, qui écrivent dans le champ ou le prompt.
 *
 * Tout ce qui touche à l'IME passe par [Host]. Toutes les méthodes sont à appeler depuis le thread principal.
 */
class SuggestionController(
    context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /** Ce que la bande de suggestions demande à l'IME. */
    interface Host {
        fun inputConnection(): InputConnection?

        /** Le mode prompt est actif : texte, curseur et barre sont ceux du prompt, pas du champ de l'application. */
        fun promptActive(): Boolean

        fun promptTextBeforeCursor(): String

        fun promptTextAfterCursor(): String

        fun barReady(): Boolean

        fun language(): KeyboardLanguage

        fun isRecording(): Boolean

        fun isCorrectionInProgress(): Boolean

        fun isEmojiPanelVisible(): Boolean

        fun isClipboardPanelVisible(): Boolean

        fun hasSelection(): Boolean

        fun isInputViewShown(): Boolean

        /** Mots du dictionnaire personnel (jamais corrigés, candidats de correction). */
        fun personalWords(): List<String>

        /** Affiche l'emoji suggéré (barre du haut, ou barre du prompt en mode prompt). */
        fun showEmoji(emoji: String?)

        /** Affiche les mots suggérés (barre du haut, ou barre du prompt en mode prompt). */
        fun showWords(words: List<WordSuggestion?>)

        /** Vide les deux barres, et fait suivre la puce de collage. */
        fun clearBars()
    }

    private val appContext = context.applicationContext

    // Story 1.16 : emoji suggéré d'après le dernier mot (4e emplacement de la barre de suggestions).
    private val emojiSuggester by lazy { EmojiSuggesterLoader.get(appContext) }

    /** Emoji actuellement proposé, s'il y en a un. */
    var currentEmojiSuggestion: String? = null
        private set

    /** Prédiction du mot suivant d'après les habitudes d'écriture (apprentissage local, chiffré). */
    val nextWords by lazy { NextWordProvider.repository(appContext) }

    /** Faux dans les champs sans suggestions et quand l'application demande de ne pas apprendre (mode privé). */
    var learningAllowed = true

    /** Faux dans les champs sans suggestions (mot de passe, e-mail, URL, nombre...), relu à chaque champ. */
    var suggestionsAllowed = true

    // Story 1.17 : mots suggérés d'après le mot en cours de frappe (3 premiers emplacements).
    var currentWordSuggestions: List<WordSuggestion?> = emptyList()
        private set

    // Entrée de la dernière mise à jour des suggestions : évite de tout recalculer quand la même
    // mise à jour est demandée deux fois de suite (touche puis onUpdateSelection).
    private var lastSuggestionInput: SuggestionInput? = null

    // Lot 1.4 : les mots du dictionnaire sont calculés hors du thread principal, « le dernier gagne » :
    // chaque nouvelle demande annule la précédente, et un résultat périmé n'est jamais affiché.
    private var suggestionJob: Job? = null
    private var suggestionSequence = 0

    private data class SuggestionInput(
        val text: String,
        val language: KeyboardLanguage,
        val available: Boolean,
        /** Suite du mot après le curseur (curseur placé dans un mot) : changer de position dans le mot relance le calcul. */
        val wordAfter: String,
    )

    /** Oublie la dernière entrée : la prochaine demande recalcule tout (nouveau champ). */
    fun invalidate() {
        lastSuggestionInput = null
    }

    /**
     * Stories 1.16 et 1.17 : met à jour les suggestions de la barre (mots et emoji) d'après le texte
     * avant le curseur. Rien n'est proposé quand le panneau emoji est ouvert, pendant une dictée ou
     * une correction, avec une sélection, ou dans un champ sans suggestions.
     *
     * Pendant la frappe d'un mot (le curseur suit une lettre, et la lettre suivante, s'il y en a une,
     * n'appartient pas au même mot), les mots sont ceux du dictionnaire (complétions, autocorrection).
     * Après une espace, ce sont les mots qui suivent le plus souvent ce qui précède, d'après les
     * habitudes d'écriture apprises sur l'appareil (le dictionnaire reste fixe). L'emoji du dernier
     * mot reste proposé après lui (story 1.16) ; à défaut, l'emoji qui suit habituellement ce contexte.
     */
    fun refresh(textBeforeCursor: String) {
        if (!host.barReady()) return
        val language = host.language()
        // Le prompt est un champ de texte libre : ni le type du champ de l'application ni sa sélection n'y comptent.
        val promptActive = host.promptActive()
        val available = (promptActive || suggestionsAllowed) && !host.isRecording() && !host.isCorrectionInProgress() &&
            !host.isEmojiPanelVisible() && !host.isClipboardPanelVisible() && (promptActive || !host.hasSelection())
        val wordAfter = if (available) wordAfterCursor() else ""
        val input = SuggestionInput(textBeforeCursor, language, available, wordAfter)
        if (input == lastSuggestionInput) return
        lastSuggestionInput = input
        cancelPendingSuggestions()
        val sequence = suggestionSequence

        var emoji = if (available) emojiSuggester.suggest(textBeforeCursor, language) else null
        val typed = if (available) typedWordForSuggestions(textBeforeCursor, wordAfter) else ""
        if (typed.isNotEmpty()) {
            // Mot en cours de frappe : complétions et autocorrection du dictionnaire, calculées hors du
            // thread principal. L'emoji s'affiche tout de suite, les mots dès que le calcul est fini.
            val dictionary = DictionaryLoader.peek(language)
            if (dictionary == null) {
                // Dictionnaire pas encore chargé : pas de mots (onDictionariesLoaded relance le calcul).
                publishSuggestions(emoji, List(Dictionary.SUGGESTION_LIMIT) { null })
                return
            }
            val personalWords = host.personalWords()
            currentWordSuggestions = emptyList() // un appui sur un mot périmé est ignoré
            publishEmoji(emoji)
            suggestionJob = scope.launch(Dispatchers.Default) {
                val started = System.nanoTime()
                val words = traced(Sections.SUGGESTIONS) {
                    dictionary.suggestionSlotsFor(typed, personalWords = personalWords)
                }
                if (BuildConfig.DEBUG) {
                    AppLog.d(TAG, "suggestions de mots en ${(System.nanoTime() - started) / 1_000} µs")
                }
                withContext(Dispatchers.Main) {
                    if (sequence == suggestionSequence && promptActive == host.promptActive()) publishWords(words)
                }
            }
            return
        }

        var words: List<WordSuggestion?> = emptyList()
        if (available) {
            // Aucun mot en cours de frappe : mots (et emoji, si l'emoji du mot précédent n'en propose pas)
            // qui suivent le plus souvent ce qui précède, d'après les habitudes d'écriture.
            val prediction = predictNext(textBeforeCursor, wordAfter)
            words = prediction.words.map { WordSuggestion(it, WordSuggestion.Kind.PREDICTION) }
            if (emoji == null) emoji = prediction.emoji
        }
        publishSuggestions(emoji, words)
    }

    /** Annule le calcul de suggestions en cours : son résultat, même terminé, ne sera pas affiché. */
    private fun cancelPendingSuggestions() {
        suggestionJob?.cancel()
        suggestionJob = null
        suggestionSequence++
    }

    private fun publishSuggestions(emoji: String?, words: List<WordSuggestion?>) {
        publishEmoji(emoji)
        publishWords(words)
    }

    private fun publishEmoji(emoji: String?) {
        currentEmojiSuggestion = emoji
        host.showEmoji(emoji)
    }

    private fun publishWords(words: List<WordSuggestion?>) {
        currentWordSuggestions = words
        host.showWords(words)
    }

    /** Vide la bande de suggestions (panneau emoji, dictée ou correction en cours). */
    fun clear() {
        lastSuggestionInput = null
        cancelPendingSuggestions()
        currentEmojiSuggestion = null
        currentWordSuggestions = emptyList()
        if (!host.barReady()) return
        host.clearBars()
    }

    /**
     * Charge FR et EN en arrière-plan dès la création du service (lot 1.4) : lecture et indexation
     * (liste française complète, ~50 000 mots en anglais), jamais sur le thread principal.
     */
    fun preloadDictionaries() {
        scope.launch(Dispatchers.IO) {
            for (language in KeyboardLanguage.values()) {
                try {
                    DictionaryLoader.forLanguage(appContext, language)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLog.w(TAG, "préchargement du dictionnaire $language", t)
                }
            }
            withContext(Dispatchers.Main) { onDictionariesLoaded() }
        }
    }

    /** Un dictionnaire vient d'être chargé : les suggestions déjà demandées sans lui sont recalculées. */
    private fun onDictionariesLoaded() {
        if (!host.isInputViewShown() || !host.barReady()) return
        lastSuggestionInput = null
        val before = if (host.promptActive()) {
            host.promptTextBeforeCursor().takeLast(TEXT_CONTEXT_LOOKBEHIND)
        } else {
            host.inputConnection()?.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        }
        refresh(before)
    }

    /** Mots et emoji probables après [textBeforeCursor] (qui doit finir par une espace), selon ce que le clavier a appris. */
    private fun predictNext(textBeforeCursor: String, wordAfter: String): NextWordModel.Prediction {
        if (!learningAllowed || textBeforeCursor.isEmpty()) return NextWordModel.Prediction.NONE
        // Curseur au milieu d'un mot : insérer un mot entier à cet endroit serait trompeur.
        if (wordAfter.isNotEmpty()) return NextWordModel.Prediction.NONE
        return nextWords.model.predict(
            textBeforeCursor,
            truncated = textBeforeCursor.length >= TEXT_CONTEXT_LOOKBEHIND,
            maxWords = SuggestionStripView.WORD_SLOT_COUNT,
        )
    }

    /**
     * Apprend, pour la prédiction du mot suivant, le dernier mot (ou emoji) terminé avant le curseur.
     * Appelé après une saisie de l'utilisateur uniquement (espace, retour à la ligne, suggestion ou emoji
     * touchés), jamais pour du texte dicté ou collé, ni dans un champ sans suggestions.
     */
    fun learnFromTyping(terminator: String = "") {
        if (!learningAllowed || host.isRecording() || host.isCorrectionInProgress()) return
        val before: String
        if (host.promptActive()) {
            // Mode prompt : on apprend du texte du prompt, jamais de celui du champ de l'application.
            before = host.promptTextBeforeCursor().takeLast(TEXT_CONTEXT_LOOKBEHIND)
        } else {
            if (host.hasSelection()) return
            before = host.inputConnection()?.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString() ?: return
        }
        // [terminator] : séparateur à considérer comme tapé après le texte (envoi du prompt sans espace finale).
        val repository = nextWords
        if (repository.model.learn(before + terminator, truncated = before.length >= TEXT_CONTEXT_LOOKBEHIND)) repository.markDirty()
    }

    /** Texte juste après le curseur : dans le prompt en mode prompt, sinon dans le champ de l'application. */
    private fun textAfterCursor(): String =
        if (host.promptActive()) host.promptTextAfterCursor().take(TEXT_CONTEXT_LOOKBEHIND)
        else host.inputConnection()?.getTextAfterCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()

    /** Suite du mot après le curseur (vide si le curseur est en fin de mot ou hors d'un mot). */
    private fun wordAfterCursor(): String = WordText.leadingWord(textAfterCursor())

    /**
     * Mot entier qui touche le curseur : ce qui le précède et ce qui le suit. Curseur en fin de mot, c'est le mot
     * en cours de frappe ; curseur placé dans un mot (tap), c'est le mot complet, pour proposer ses corrections.
     * Vide si le curseur n'est contre aucun mot.
     */
    private fun typedWordForSuggestions(textBeforeCursor: String, wordAfter: String): String =
        WordText.trailingWord(textBeforeCursor) + wordAfter

    /**
     * Autocorrection de la fin de [textBeforeCursor] au moment où un mot se termine : correction du dictionnaire pour
     * le mot qui vient d'être tapé, puis, en français, correction tenant compte des mots voisins (« a » / « à »,
     * infinitif / participe passé : voir [FrenchContextCorrector]). Renvoie null s'il n'y a rien à changer.
     * [allowRetro] : autorise la révision du mot précédent (désactivée juste après une annulation de l'utilisateur).
     */
    fun autocorrectionFor(textBeforeCursor: String, allowRetro: Boolean): AutocorrectionEdit? {
        val word = WordText.trailingWord(textBeforeCursor)
        if (word.isEmpty()) return null
        val dictionaryFix = dictionaryCorrectionFor(word)
        var result = if (dictionaryFix != null) textBeforeCursor.dropLast(word.length) + dictionaryFix else textBeforeCursor
        if (host.language() == KeyboardLanguage.FR) {
            val dictionary = DictionaryLoader.peek(KeyboardLanguage.FR)
            if (dictionary != null) {
                val contextual = FrenchContextCorrector.correct(
                    text = result,
                    textMayBeTruncated = textBeforeCursor.length >= TEXT_CONTEXT_LOOKBEHIND,
                    allowRetro = allowRetro,
                    frequencyOf = dictionary::frequencyOf,
                )
                if (contextual != null) result = contextual
            }
        }
        if (result == textBeforeCursor) return null
        var common = 0
        val limit = minOf(result.length, textBeforeCursor.length)
        while (common < limit && result[common] == textBeforeCursor[common]) common++
        return AutocorrectionEdit(deleteCount = textBeforeCursor.length - common, insert = result.substring(common))
    }

    /**
     * Correction du dictionnaire pour [word], ou null s'il n'y en a pas. Story 1.4 : les mots du
     * dictionnaire personnel ne sont jamais corrigés et servent aussi de candidats de correction.
     */
    fun dictionaryCorrectionFor(word: String): String? {
        // Lot 1.4 : jamais d'attente du chargement sur le thread principal. Tant que le dictionnaire de la
        // langue n'est pas prêt (quelques instants après la création du service), pas d'autocorrection.
        val dictionary = DictionaryLoader.peek(host.language()) ?: return null
        val started = System.nanoTime()
        val correction = traced(Sections.AUTOCORRECTION) {
            dictionary.correctionFor(word, personalWords = host.personalWords())
        }
        if (BuildConfig.DEBUG) {
            AppLog.d(TAG, "autocorrection de \"$word\" en ${(System.nanoTime() - started) / 1_000} µs")
        }
        if (correction == null || correction == word) return null
        return correction
    }

    private companion object {
        private const val TAG = "SuggestionController"
    }
}
