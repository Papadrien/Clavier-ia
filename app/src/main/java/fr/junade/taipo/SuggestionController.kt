package fr.junade.taipo

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.InputConnection
import fr.junade.taipo.dictionary.CorrectionHabits
import fr.junade.taipo.dictionary.CorrectionHabitsProvider
import fr.junade.taipo.dictionary.CorrectionHistory
import fr.junade.taipo.dictionary.Dictionary
import fr.junade.taipo.dictionary.DictionaryFiles
import fr.junade.taipo.dictionary.NextLetterModel
import fr.junade.taipo.dictionary.DictionaryLoader
import fr.junade.taipo.dictionary.download.DictionaryDownloadScheduler
import fr.junade.taipo.dictionary.download.DictionaryDownloadState
import fr.junade.taipo.dictionary.download.DictionaryDownloadTracker
import fr.junade.taipo.dictionary.FrenchContextCorrector
import fr.junade.taipo.dictionary.HabitContext
import fr.junade.taipo.dictionary.HabitSignals
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.suggestion.EmailText
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

        /** Un mot touché pendant la dictée attend sa correction : la bande propose ses alternatives malgré l'écoute. */
        fun voiceWordPicked(): Boolean = false

        /** Ce qui peut suivre le mot en cours de frappe (null : aucune prédiction), pour les zones de frappe adaptatives. */
        fun showNextLetters(prediction: NextLetterModel.Probabilities?) {}
    }

    private val appContext = context.applicationContext

    /** Dictionnaire téléchargé pendant que le clavier est ouvert : la langue active se charge sans attendre le prochain champ. */
    private val dictionaryDownloadListener = DictionaryDownloadTracker.Listener { language, state ->
        if (state == DictionaryDownloadState.Installed) {
            scope.launch(Dispatchers.Main) { if (language == host.language()) preloadDictionaries() }
        }
    }

    init {
        DictionaryDownloadTracker.addListener(dictionaryDownloadListener)
    }

    /** À appeler à la destruction du service. */
    fun release() {
        DictionaryDownloadTracker.removeListener(dictionaryDownloadListener)
    }

    // Story 1.16 : emoji suggéré d'après le dernier mot (4e emplacement de la barre de suggestions).
    private val emojiSuggester by lazy { EmojiSuggesterLoader.get(appContext) }

    /** Emoji actuellement proposé, s'il y en a un. */
    var currentEmojiSuggestion: String? = null
        private set

    /** Prédiction du mot suivant d'après les habitudes d'écriture (apprentissage local, chiffré). */
    val nextWords by lazy { NextWordProvider.repository(appContext) }

    /** Faux dans les champs sans suggestions et quand l'application demande de ne pas apprendre (mode privé). */
    var learningAllowed = true

    /** Habitudes de correction : comptées (phase 2), refus après 2 annulations (phase 3), départage de candidats à distance égale (phase 4). */
    val correctionHabits by lazy { CorrectionHabitsProvider.repository(appContext) }
    private val habitSignals by lazy { HabitSignals(correctionHabits.habits) }

    // Réglage « Apprendre de mes habitudes », relu à chaque champ.
    private var habitLearningEnabled = KeyboardPreferences.DEFAULT_HABIT_LEARNING

    /** Compter les habitudes : mêmes interdictions que la prédiction du mot suivant (champ sensible, navigation privée) + le réglage. */
    private val habitsAllowed: Boolean get() = learningAllowed && habitLearningEnabled

    /**
     * Les adresses e-mail peuvent être apprises et proposées dans ce champ : texte libre ou champ d'adresse e-mail (là où
     * elles servent le plus, alors que les autres suggestions y sont coupées), jamais mot de passe, adresse web ni
     * navigation privée. Relu à chaque champ (faux tant qu'il n'a pas été posé).
     */
    var emailFieldAllowed = false

    /** Le champ est un champ d'adresse e-mail : les adresses y sont proposées dès la première lettre. */
    var isEmailAddressField = false

    // Adresses : mêmes interdictions que les habitudes, plus le réglage « Apprendre de mes habitudes ».
    private val emailsAllowed: Boolean get() = emailFieldAllowed && habitLearningEnabled

    // Dernière adresse retenue, tant que rien n'a été tapé depuis : évite de la compter deux fois (retour à la ligne puis sortie du champ).
    private var lastEmailRecorded: String? = null

    // Une touche du clavier a été pressée dans ce champ : seul du texte tapé (pas collé ni rempli par une appli) est retenu à la sortie du champ.
    private var typedInField = false

    /** À appeler à chaque nouveau champ, une fois [learningAllowed] à jour : relit le réglage et oublie les corrections en cours. */
    fun refreshHabitLearning(enabled: Boolean) {
        habitLearningEnabled = enabled
        habitSignals.reset() // une correction suivie dans un autre champ (ou quand l'apprentissage est interdit) ne se compte pas
    }

    /**
     * Une autocorrection vient d'être appliquée (à signaler avant [learnFromTyping] de la même touche) :
     * [original] (fin de [textBeforeCursor]) a été remplacé par [corrected].
     */
    fun habitAutocorrectionApplied(textBeforeCursor: String, original: String, corrected: String) {
        if (!habitsAllowed) return
        habitSignals.autocorrectionApplied(host.language(), textBeforeCursor, original, corrected)
    }

    /** Phase 3 : vrai si l'utilisateur a déjà refusé (annulé) cette correction du mot [typed] au moins deux fois. */
    private fun refusedByUser(typed: String, corrected: String): Boolean =
        habitsAllowed && correctionHabits.habits.isRefused(host.language(), typed, corrected)

    // Fois où les habitudes ont changé la correction du dictionnaire depuis le démarrage (compteur de debug, jamais de texte).
    private var habitTieBreaks = 0

    /**
     * Phase 4 : habitudes à consulter pour départager des corrections à distance égale (acceptée, puis contexte, puis
     * usage), pour le mot qui termine [textBeforeCursor]. Null si l'apprentissage est désactivé ou interdit dans ce
     * champ : la correction est alors celle d'avant. Le contexte est le mot qui précède, d'après les bigrammes du mot suivant.
     */
    private fun habitContextFor(textBeforeCursor: String): HabitContext? {
        if (!habitsAllowed) return null
        val before = textBeforeCursor.dropLast(WordText.trailingWord(textBeforeCursor).length)
        val previous = HabitContext.previousWordOf(before, truncated = textBeforeCursor.length >= TEXT_CONTEXT_LOOKBEHIND)
        return HabitContext(correctionHabits.habits, host.language(), nextWords.model, previous)
    }

    /** L'utilisateur vient d'annuler une autocorrection. */
    fun habitAutocorrectionUndone(original: String, corrected: String) {
        if (!habitsAllowed) return
        if (habitSignals.autocorrectionUndone(original, corrected)) onHabitsChanged()
    }

    private fun onHabitsChanged() {
        correctionHabits.markDirty()
        // Compteurs de debug uniquement (builds debug) : jamais de texte saisi.
        AppLog.d(
            TAG,
            "Habitudes : annulations=${habitSignals.recordedUndos} acceptations=${habitSignals.recordedAccepted} " +
                "usages=${habitSignals.recordedUsages} adresses=${habitSignals.recordedEmails} entrées=${correctionHabits.habits.size()}",
        )
    }

    /** Faux dans les champs sans suggestions (mot de passe, e-mail, URL, nombre...), relu à chaque champ. */
    var suggestionsAllowed = true

    /**
     * Zones de frappe adaptatives permises dans ce champ (réglage actif, hors champ sensible, e-mail et adresse web),
     * relu à chaque champ. Faux : aucune prédiction de lettre, le clavier garde ses zones nominales.
     */
    var nextLettersAllowed = false

    // Une prédiction non nulle a été transmise au clavier : à retirer quand elle n'est plus permise.
    private var nextLettersPublished = false

    /**
     * Transmet au clavier la prédiction de la lettre suivante d'après [textBeforeCursor] : synchrone, avant la frappe
     * suivante, sur le thread principal. Rien tant que le modèle de la langue n'est pas construit (le préchargement le
     * construit en arrière-plan, voir [preloadDictionaries]) : la construction ne se fait jamais ici.
     */
    private fun updateNextLetters(textBeforeCursor: String) {
        val model = if (nextLettersAllowed) DictionaryLoader.peek(host.language())?.nextLetterModelIfBuilt else null
        val prediction = model?.let { NextLetterContext.predict(it, textBeforeCursor) }
        if (prediction == null && !nextLettersPublished) return
        nextLettersPublished = prediction != null
        host.showNextLetters(prediction)
    }

    // Construit le modèle de la lettre suivante hors du thread principal, si les zones adaptatives sont permises.
    private fun warmNextLetterModel(language: KeyboardLanguage) {
        if (nextLettersAllowed) DictionaryLoader.peek(language)?.let { it.nextLetterModel }
    }

    /** Faux dans les champs sans emoji suggérés (adresse web...), relu à chaque champ. */
    var emojiAllowed = true

    /**
     * Faux dans les champs où les mots sont proposés sans autocorrection (adresse web...), relu à chaque champ : la correction
     * y est affichée comme une simple suggestion (poids normal), qui ne remplace rien à l'espace. Le mode prompt n'est pas concerné.
     */
    var autocorrectsInField = true

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
        /** Curseur posé sur un mot déjà écrit (et non en train d'en taper un) : mêmes lettres, autres propositions. */
        val placement: Boolean,
        /** Adresses e-mail proposables (champ et réglage) : en changer relance le calcul. */
        val emailAvailable: Boolean,
    )

    // Mots corrigés par l'autocorrection pendant cette saisie : curseur posé sur l'un d'eux, l'emplacement de gauche
    // propose de revenir à la forme d'origine.
    private val correctionHistory = CorrectionHistory()

    // Instant de la dernière touche du clavier : tant qu'elle est récente, le curseur « suit » la frappe ; sinon il a été posé.
    private var lastTypingAt = 0L

    /** Une touche du clavier vient d'être pressée : le curseur qui bouge ensuite suit la frappe, il n'a pas été posé. */
    fun markTyping() {
        lastTypingAt = SystemClock.uptimeMillis()
        // Les touches tapées dans le prompt ne disent rien du texte du champ de l'application.
        if (!host.promptActive()) typedInField = true
        lastEmailRecorded = null
    }

    private fun plainIfNoAutocorrect(words: List<WordSuggestion?>, promptActive: Boolean): List<WordSuggestion?> {
        if (autocorrectsInField || promptActive) return words
        return words.map {
            if (it != null && it.kind == WordSuggestion.Kind.AUTOCORRECTION) it.copy(kind = WordSuggestion.Kind.COMPLETION) else it
        }
    }

    /** Une touche du clavier a été pressée il y a peu : le curseur suit la frappe, il n'a pas été posé par un toucher. */
    fun typedRecently(): Boolean = SystemClock.uptimeMillis() - lastTypingAt <= TYPING_WINDOW_MS

    /** Retient qu'[original] (tel que tapé) a été remplacé par [corrected] (voir [CorrectionHistory.record]). */
    fun recordCorrection(original: String, corrected: String) = correctionHistory.record(original, corrected)

    /**
     * Retient une autocorrection qui vient d'être appliquée à [textBeforeCursor] (voir [CorrectionHistory.recordEdit]) :
     * [edit] ne couvre que la fin des mots modifiés, l'historique garde les mots entiers tels que tapés.
     */
    fun recordCorrection(textBeforeCursor: String, edit: AutocorrectionEdit) =
        correctionHistory.recordEdit(textBeforeCursor, edit.deleteCount, edit.insert)

    /** Oublie la dernière entrée : la prochaine demande recalcule tout (nouveau champ). */
    fun invalidate() {
        lastSuggestionInput = null
        correctionHistory.clear()
        typedInField = false
        lastEmailRecorded = null
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
        updateNextLetters(textBeforeCursor)
        if (!host.barReady()) return
        val language = host.language()
        // Le prompt est un champ de texte libre : ni le type du champ de l'application ni sa sélection n'y comptent.
        val promptActive = host.promptActive()
        // Mot touché pendant la dictée : la bande propose ses alternatives (pas d'emoji, d'adresse ni de mot suivant).
        val voiceWordPick = host.isRecording() && host.voiceWordPicked()
        val idle = (!host.isRecording() || voiceWordPick) && !host.isCorrectionInProgress() &&
            !host.isEmojiPanelVisible() && !host.isClipboardPanelVisible() && (promptActive || !host.hasSelection())
        val available = (promptActive || suggestionsAllowed) && idle
        val emailAvailable = emailsAllowed && idle && !voiceWordPick
        val wordAfter = if (available) wordAfterCursor() else ""
        // Curseur dans un mot, ou posé là sans frappe récente : le mot est déjà écrit, on propose des alternatives.
        val placement = voiceWordPick || wordAfter.isNotEmpty() || SystemClock.uptimeMillis() - lastTypingAt > TYPING_WINDOW_MS
        val input = SuggestionInput(textBeforeCursor, language, available, wordAfter, placement, emailAvailable)
        if (input == lastSuggestionInput) return
        lastSuggestionInput = input
        cancelPendingSuggestions()
        val sequence = suggestionSequence

        // Adresse e-mail en cours de frappe : proposée seule si c'est clairement une adresse (« @ » tapé, champ d'adresse),
        // sinon dans le dernier emplacement, à côté des mots (placement : curseur posé sur un mot, rien n'est proposé).
        val emailChoice = emailChoiceFor(textBeforeCursor, usable = emailAvailable, placement = placement)
        if (publishExclusiveEmails(emailChoice)) return
        val emailSuggestion = emailChoice?.suggestions?.first()

        var emoji = if (available && emojiAllowed && !voiceWordPick) emojiSuggester.suggest(textBeforeCursor, language) else null
        val typed = if (available) typedWordForSuggestions(textBeforeCursor, wordAfter) else ""
        if (typed.isNotEmpty()) {
            // Mot en cours de frappe : complétions et autocorrection du dictionnaire, calculées hors du
            // thread principal. L'emoji s'affiche tout de suite, les mots dès que le calcul est fini.
            val dictionary = DictionaryLoader.peek(language)
            if (dictionary == null) {
                // Dictionnaire pas encore chargé : pas de mots (onDictionariesLoaded relance le calcul).
                publishSuggestions(emoji, withEmail(List(Dictionary.SUGGESTION_LIMIT) { null }, emailSuggestion))
                return
            }
            val personalWords = host.personalWords()
            val habits = habitContextFor(textBeforeCursor) // lu ici (thread principal), consulté pendant le calcul
            currentWordSuggestions = emptyList() // un appui sur un mot périmé est ignoré
            publishEmoji(emoji)
            suggestionJob = scope.launch(Dispatchers.Default) {
                val words = traced(Sections.SUGGESTIONS) {
                    if (placement) {
                        dictionary.placementSlotsFor(
                            typed,
                            personalWords,
                            previousForm = correctionHistory.originalOf(typed),
                            habits = habits,
                        )
                    } else {
                        dictionary.suggestionSlotsFor(typed, personalWords = personalWords, habits = habits)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (sequence == suggestionSequence && promptActive == host.promptActive()) publishWords(withEmail(plainIfNoAutocorrect(words, promptActive), emailSuggestion))
                }
            }
            return
        }

        var words: List<WordSuggestion?> = emptyList()
        if (available && !voiceWordPick) {
            // Aucun mot en cours de frappe : mots (et emoji, si l'emoji du mot précédent n'en propose pas)
            // qui suivent le plus souvent ce qui précède, d'après les habitudes d'écriture.
            val prediction = predictNext(textBeforeCursor, wordAfter)
            words = prediction.words.map { WordSuggestion(it, WordSuggestion.Kind.PREDICTION) }
            if (emoji == null) emoji = prediction.emoji
        }
        publishSuggestions(emoji, withEmail(words, emailSuggestion))
    }

    /** Adresses proposées pour ce que l'utilisateur tape ; [exclusive] : elles remplacent les mots de la bande. */
    private class EmailChoice(emails: List<String>, val exclusive: Boolean) {
        val suggestions: List<WordSuggestion> = emails.map { WordSuggestion(it, WordSuggestion.Kind.EMAIL) }
    }

    /** Vrai (et les adresses sont publiées, seules) si [choice] remplace les mots de la bande. */
    private fun publishExclusiveEmails(choice: EmailChoice?): Boolean {
        if (choice == null || !choice.exclusive) return false
        publishSuggestions(null, choice.suggestions)
        return true
    }

    /**
     * Adresses déjà tapées qui commencent comme le jeton qui touche le curseur, ou null si rien n'est pertinent :
     * jeton coupé au début de la fenêtre, curseur au milieu d'une adresse, jeton trop court (3 caractères dans un
     * texte libre pour ne pas encombrer la frappe d'un mot ordinaire ; une seule dans un champ d'adresse ou dès
     * qu'un « @ » est tapé) ou aucune adresse qui commence ainsi. [usable] : adresses proposables dans ce champ ;
     * [placement] : curseur posé sur un mot déjà écrit, rien n'est proposé.
     */
    private fun emailChoiceFor(textBeforeCursor: String, usable: Boolean, placement: Boolean): EmailChoice? {
        if (!usable || placement) return null
        val token = EmailText.trailingToken(textBeforeCursor, truncated = textBeforeCursor.length >= TEXT_CONTEXT_LOOKBEHIND) ?: return null
        val clearlyAnEmail = '@' in token || (isEmailAddressField && !host.promptActive())
        if (token.length < (if (clearlyAnEmail) 1 else MIN_EMAIL_PREFIX_LENGTH)) return null
        // Curseur placé dans une adresse déjà écrite : rien à compléter.
        if (textAfterCursor().firstOrNull()?.let { EmailText.isEmailChar(it) } == true) return null
        val emails = correctionHabits.habits.suggestEmails(token, SuggestionStripView.WORD_SLOT_COUNT)
        if (emails.isEmpty()) return null
        return EmailChoice(emails, exclusive = clearlyAnEmail)
    }

    /** [words] dont le dernier emplacement est pris par [email] (s'il y en a une) : les deux premiers mots restent visibles. */
    private fun withEmail(words: List<WordSuggestion?>, email: WordSuggestion?): List<WordSuggestion?> {
        if (email == null) return words
        val slots = MutableList<WordSuggestion?>(SuggestionStripView.WORD_SLOT_COUNT) { words.getOrNull(it) }
        slots[slots.size - 1] = email
        return slots
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
     * Charge le dictionnaire de la langue active en arrière-plan (lot 1.4) : lecture et indexation
     * (liste française complète, ~50 000 mots pour les autres langues), jamais sur le thread principal.
     * Appelé à la création du service puis à chaque ouverture de champ : sans effet si la langue est déjà
     * chargée, et un changement de langue dans les paramètres charge la nouvelle langue ici.
     */
    fun preloadDictionaries() {
        val language = host.language()
        if (DictionaryLoader.peek(language) != null) {
            // Dictionnaire déjà là : seul le modèle de la lettre suivante peut manquer (réglage activé depuis).
            if (nextLettersAllowed && DictionaryLoader.peek(language)?.nextLetterModelIfBuilt == null) {
                scope.launch(Dispatchers.Default) {
                    warmNextLetterModel(language)
                    withContext(Dispatchers.Main) { onDictionariesLoaded() }
                }
            }
            return
        }
        // Les dictionnaires ne sont plus dans l'APK : langue pas encore téléchargée (première utilisation, ou mise à jour
        // depuis une version qui les embarquait) = on lance le téléchargement ; le chargement suit à son arrivée.
        if (!DictionaryFiles.isInstalled(appContext.filesDir, language)) {
            DictionaryDownloadScheduler.ensure(appContext, language)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                DictionaryLoader.forLanguage(appContext, language)
                warmNextLetterModel(language)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "préchargement du dictionnaire $language", t)
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
        if (host.isRecording() || host.isCorrectionInProgress()) return
        // Les adresses e-mail s'apprennent aussi dans les champs d'adresse, où les mots ne s'apprennent pas.
        if (!learningAllowed && !emailsAllowed) return
        val before: String
        if (host.promptActive()) {
            // Mode prompt : on apprend du texte du prompt, jamais de celui du champ de l'application.
            before = host.promptTextBeforeCursor().takeLast(TEXT_CONTEXT_LOOKBEHIND)
        } else {
            if (host.hasSelection()) return
            before = host.inputConnection()?.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString() ?: return
        }
        // [terminator] : séparateur à considérer comme tapé après le texte (envoi du prompt sans espace finale).
        val text = before + terminator
        val truncated = before.length >= TEXT_CONTEXT_LOOKBEHIND
        if (emailsAllowed) recordTypedEmail(text, truncated)
        if (!learningAllowed) return
        if (habitsAllowed && habitSignals.wordCompleted(host.language(), text, truncated)) onHabitsChanged()
        // Une adresse n'est pas une suite de mots : « jean.dupont@gmail.com » n'apprend pas « com » après un début de phrase.
        if (EmailText.completedEmail(text, truncated) != null) return
        val repository = nextWords
        if (repository.model.learn(text, truncated = truncated)) repository.markDirty()
    }

    /** Retient l'adresse que [textBeforeCursor] vient de terminer, une seule fois tant que rien n'est tapé de plus. */
    private fun recordTypedEmail(textBeforeCursor: String, truncated: Boolean) {
        val email = EmailText.completedEmail(textBeforeCursor, truncated) ?: return
        if (email == lastEmailRecorded) return
        if (habitSignals.emailCompleted(textBeforeCursor, truncated)) {
            lastEmailRecorded = email
            onHabitsChanged()
        }
    }

    /**
     * À appeler quand le clavier quitte un champ : une adresse tapée en dernier, sans espace ni retour à la ligne
     * après elle (champ d'adresse e-mail, formulaire de connexion), est retenue. Seulement si l'utilisateur a tapé
     * dans ce champ, jamais en mode prompt (le texte du champ n'est alors pas le sien).
     */
    fun finishField() {
        if (!typedInField || !emailsAllowed || host.promptActive()) return
        typedInField = false
        val before = host.inputConnection()?.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString() ?: return
        // Terminée par une espace ou un retour à la ligne, elle a déjà été retenue à ce moment-là.
        if (before.isEmpty() || before.last().isWhitespace()) return
        recordTypedEmail(before, truncated = before.length >= TEXT_CONTEXT_LOOKBEHIND)
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
        // Une adresse e-mail en cours de frappe n'est jamais corrigée (« gmal », « com »...) : l'utilisateur la tape telle quelle.
        val token = EmailText.trailingToken(textBeforeCursor, truncated = textBeforeCursor.length >= TEXT_CONTEXT_LOOKBEHIND)
        if (token != null && token.indexOf('@') > 0) return null
        // Phase 3 : une correction annulée deux fois n'est plus appliquée (le mot reste tel que tapé).
        val dictionaryFix = dictionaryCorrectionFor(word, habitContextFor(textBeforeCursor))?.takeUnless { refusedByUser(word, it) }
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
        val edit = AutocorrectionEdit(deleteCount = textBeforeCursor.length - common, insert = result.substring(common))
        // Même règle pour les corrections de contexte (« a » / « à »...) : on regarde le mot réellement changé.
        val changed = HabitSignals.changedWord(textBeforeCursor, edit.deleteCount, edit.insert)
        if (changed != null && refusedByUser(changed.first, changed.second)) {
            AppLog.d(TAG, "Habitudes : correction ignorée, annulée au moins ${CorrectionHabits.REFUSAL_THRESHOLD} fois")
            return null
        }
        return edit
    }

    /**
     * Correction du dictionnaire pour [word], ou null s'il n'y en a pas. Story 1.4 : les mots du
     * dictionnaire personnel ne sont jamais corrigés et servent aussi de candidats de correction.
     * Phase 4 : [habits] départage les candidats à distance égale (voir [habitContextFor]).
     */
    fun dictionaryCorrectionFor(word: String, habits: HabitContext? = null): String? {
        // Lot 1.4 : jamais d'attente du chargement sur le thread principal. Tant que le dictionnaire de la
        // langue n'est pas prêt (quelques instants après la création du service), pas d'autocorrection.
        val dictionary = DictionaryLoader.peek(host.language()) ?: return null
        val personalWords = host.personalWords()
        val correction = traced(Sections.AUTOCORRECTION) {
            dictionary.correctionFor(word, personalWords = personalWords, habits = habits)
        }
        if (BuildConfig.DEBUG && habits != null && correction != dictionary.correctionFor(word, personalWords = personalWords)) {
            habitTieBreaks++
            AppLog.d(TAG, "Habitudes : le départage a changé la correction ($habitTieBreaks fois depuis le démarrage)")
        }
        if (correction == null || correction == word) return null
        return correction
    }

    private companion object {
        private const val TAG = "SuggestionController"

        /** Durée après une touche pendant laquelle un curseur qui bouge est considéré comme suivant la frappe. */
        private const val TYPING_WINDOW_MS = 1_200L

        /** Caractères tapés avant de proposer une adresse e-mail dans un texte libre (sans « @ » ni champ d'adresse). */
        private const val MIN_EMAIL_PREFIX_LENGTH = 3

    }
}
