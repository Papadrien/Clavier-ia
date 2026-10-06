package fr.junade.taipo

import android.content.Context
import android.os.Handler
import fr.junade.taipo.ai.ChatExchange
import fr.junade.taipo.ai.FieldContext
import fr.junade.taipo.ai.GenerationPromptPreferences
import fr.junade.taipo.ai.GenerationSession
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.ai.PromptConversation
import fr.junade.taipo.ai.PromptMessageStatus
import fr.junade.taipo.ai.VoiceField
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.model.AiModel
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Mode prompt (épopée 5) : état du mode, tampon de saisie du prompt, conversation, génération en
 * streaming et vues (zone de chat, barre du prompt, bande de suggestions). Extrait de `TaipoIme`
 * au lot 2.3 de la revue de code, sans changement de comportement.
 *
 * Reste dans l'IME, pour les étapes suivantes du lot 2.3 : le traitement des touches en mode prompt
 * (autocorrection, annulation, double espace, apprentissage), qui dépend des suggestions.
 * L'IME lit [active] et [buffer] pour aiguiller les frappes.
 *
 * Toutes les méthodes sont à appeler depuis le thread principal.
 */
class PromptModeController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val llmHost: LlmEngineHost,
    private val host: Host,
) {

    /** Ce que le mode prompt demande à l'IME. */
    interface Host {
        fun showMessage(message: String)

        fun haptic()

        /** Le curseur du prompt a été déplacé d'une touche dans le texte : resynchroniser majuscule auto et suggestions. */
        fun onPromptCursorMoved()

        fun isRecording(): Boolean

        /** Une dictée est en cours : elle est annulée (la saisie du prompt disparaît avec le mode). */
        fun cancelVoice()

        fun isCorrectionInProgress(): Boolean

        /** La barre normale du haut est créée. */
        fun barReady(): Boolean

        fun setNormalBarVisible(visible: Boolean)

        fun collapseMenu()

        /** Referme le panneau emoji, s'il existe, sans resynchroniser. */
        fun hideEmojiPanel()

        /** Referme le panneau presse-papiers sans resynchroniser. */
        fun hideClipboardPanel()

        fun refreshRecentEmojiBar()

        fun clearPendingAutocorrection()

        fun clearSuggestions()

        /** Interrompt un balayage de suppression en cours. */
        fun cancelDeleteSwipe()

        fun syncAutoCapitalization()

        fun applyState()

        fun updateCorrectionBarVisibility()

        fun activeModel(): AiModel?

        /** Texte du champ de l'application (capturé pour la story 5.2), ou null. */
        fun capturedFieldText(): String?

        /** Le dernier mot du prompt est terminé par l'envoi. */
        fun learnFromTyping(terminator: String)

        /** Story 5.3 : insère [text] au curseur de l'application ; faux si le champ n'est pas accessible. */
        fun insertGeneratedText(text: String): Boolean

        fun onWordSuggestionTapped(suggestion: WordSuggestion)

        fun onEmojiSuggestionTapped()
    }

    /** Story 5.1 (phase 5.1-2) : zone de chat, premier enfant de la racine, masquée par défaut. */
    lateinit var chatZone: ChatZoneView
        private set

    /** Phase 5.1-4 : barre du haut en mode prompt, qui remplace la barre normale tant que le mode est actif. */
    lateinit var promptBar: PromptBarView
        private set

    /**
     * Rangée de suggestions de mots et d'emoji du mode prompt : la barre normale, qui porte la bande
     * habituelle, est remplacée par [promptBar] pendant le mode, la bande s'affiche donc ici.
     */
    lateinit var suggestionBar: PromptSuggestionBarView
        private set

    /** Vrai tant que le mode prompt est actif (entré par « Générer », quitté par la croix « annuler »). */
    var active = false
        private set

    /** Phase 5.1-5 : texte du prompt en cours de saisie ; en mode prompt, les frappes vont ici et non dans le champ de l'application. */
    val buffer = PromptInputBuffer()

    /**
     * Dictée dans le prompt : la transcription s'insère dans [buffer] et la pilule est redessinée. Inerte une fois
     * le mode quitté (voir [PromptBufferVoiceField]).
     */
    val voiceField: VoiceField = PromptBufferVoiceField(buffer, isActive = { active }, onChanged = { refreshInput() })

    /**
     * Un prompt a déjà été envoyé : le bouton afficher/masquer le chat existe alors (décisions 5 et 14).
     * Vrai dès l'envoi du premier prompt (phase 5.1-6), faux de nouveau à la fermeture du clavier.
     */
    private var chatAvailable = false

    /** La zone de chat est affichée (bouton afficher/masquer) ; mémorisé d'une entrée à l'autre du mode prompt. */
    private var chatShown = true

    /**
     * Phase 5.1-6 : conversation du mode prompt (prompts et réponses, partielles comprises). Portée
     * par le service pour survivre à la sortie du mode prompt (croix « annuler », décision 14) ; vidée à
     * la fermeture du clavier ([resetConversation]).
     */
    private val conversation = PromptConversation()

    // Session de génération créée au premier envoi seulement (elle s'enregistre auprès du moteur partagé).
    private val generationSessionLazy = lazy {
        val promptPreferences = GenerationPromptPreferences(context)
        GenerationSession(llmHost, systemPrompt = { promptPreferences.get() })
    }
    private val generationSession: GenerationSession get() = generationSessionLazy.value
    private var generationJob: Job? = null

    /** Décision 18 : chargement du modèle lancé à l'entrée du mode prompt, pendant que l'utilisateur saisit. */
    private var preloadJob: Job? = null

    val isGenerating: Boolean get() = conversation.isGenerating

    /** Crée les trois vues (masquées) : à ajouter à la hiérarchie de vues par l'IME. */
    fun createViews() {
        chatZone = ChatZoneView(context)
        chatZone.visibility = View.GONE
        chatZone.setOnAddTextClickListener { index -> onAddTextRequested(index) }

        // Barre du mode prompt (phase 5.1-4) ; stop et croix pendant la génération : phase 5.1-7.
        promptBar = PromptBarView(context)
        promptBar.modelLoading = llmHost.isLoading
        llmHost.setLoadingListener { loading -> mainHandler.post { onModelLoadingChanged(loading) } }
        promptBar.visibility = View.GONE
        promptBar.setOnCancelClickListener { onCancelRequested() }
        promptBar.setOnSendClickListener {
            host.haptic()
            requestSend()
        }
        promptBar.setOnStopClickListener {
            host.haptic()
            stopGeneration()
        }
        promptBar.setOnCursorTapListener { position ->
            if (active && buffer.setCursor(position)) {
                refreshInput()
                host.onPromptCursorMoved()
            }
        }
        promptBar.setOnChatToggleClickListener {
            chatShown = !chatShown
            applyViews()
        }

        // Suggestions de mots et d'emoji du prompt : mêmes actions que la bande de la barre du haut.
        suggestionBar = PromptSuggestionBarView(context)
        suggestionBar.visibility = View.GONE
        suggestionBar.setOnWordClickListener { suggestion -> host.onWordSuggestionTapped(suggestion) }
        suggestionBar.setOnEmojiClickListener { host.onEmojiSuggestionTapped() }
    }

    /** État du bouton micro de la barre du prompt (écoute, chargement…), suivi depuis la dictée. */
    fun setVoiceState(state: VoiceBarState) {
        if (this::promptBar.isInitialized) promptBar.voiceState = state
    }

    fun setSuggestionEmoji(emoji: String?) {
        suggestionBar.setEmoji(emoji)
    }

    fun setSuggestionWords(words: List<WordSuggestion?>) {
        suggestionBar.setWords(words)
    }

    /** Vide la bande de suggestions du prompt (si les vues existent). */
    fun clearSuggestionBar() {
        if (this::suggestionBar.isInitialized) {
            suggestionBar.setEmoji(null)
            suggestionBar.setWords(emptyList())
        }
    }

    // ------------------------------------------------------------------
    // Mode prompt (story 5.1, phase 5.1-4 : bascule de la barre)
    // ------------------------------------------------------------------

    /** Touche sur « Générer » : la barre du haut devient la zone de saisie du prompt (décision 1). */
    fun enter() {
        if (active || !this::promptBar.isInitialized) return
        if (host.isRecording() || host.isCorrectionInProgress() || generationBusy()) {
            host.showMessage(context.getString(R.string.ai_action_busy))
            return
        }
        host.haptic()
        host.hideEmojiPanel()
        host.hideClipboardPanel()
        host.collapseMenu()
        host.clearPendingAutocorrection() // pas d'annulation d'autocorrection sur du texte du champ pendant le mode
        buffer.clear()
        active = true
        host.clearSuggestions() // les suggestions du champ de l'application ne valent pas pour le prompt
        refreshInput()
        // Conversation retrouvée à la réouverture du mode (décision 14) : bulles reconstruites, défilement en bas.
        if (!conversation.isEmpty) chatZone.showMessages(conversation.messages)
        applyViews()
        host.syncAutoCapitalization() // majuscule en début de prompt
        host.applyState()
        preloadModel()
    }

    /**
     * Décision 18 : charge le modèle actif en arrière-plan dès l'entrée dans le mode prompt, pendant que
     * l'utilisateur saisit son message. Sans modèle sélectionné, rien à faire (le pop-up viendra à
     * l'envoi, comme avant). Un échec de chargement est seulement journalisé ici : [requestSend]
     * relance le chargement à l'envoi et affiche alors l'erreur. Si le modèle est déjà chargé, ou si un
     * préchargement est déjà en cours, rien n'est relancé.
     */
    private fun preloadModel() {
        if (preloadJob?.isActive == true) return
        val model = host.activeModel() ?: return
        preloadJob = scope.launch {
            try {
                llmHost.preload(model)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppLog.w(TAG, "préchargement du modèle", t)
            }
        }
    }

    /**
     * Décision 8.7 : le chargement du modèle commence ou se termine (préchargement, chargement à l'envoi
     * ou rechargement après un changement de modèle). La barre du prompt affiche l'indicateur ; si un
     * échange attend le moteur sans rien avoir reçu, sa bulle dit « Chargement du modèle… » puis revient à « … ».
     */
    private fun onModelLoadingChanged(loading: Boolean) {
        if (this::promptBar.isInitialized) promptBar.modelLoading = loading
        if (this::chatZone.isInitialized && conversation.isGenerating &&
            conversation.messages.lastOrNull()?.response.isNullOrEmpty()
        ) {
            chatZone.setLastResponseText(
                context.getString(if (loading) R.string.prompt_loading_model else R.string.prompt_response_pending),
            )
        }
    }

    /**
     * Exclusion mutuelle correction / dictée / génération : vrai tant qu'une génération occupe le moteur,
     * y compris le court instant où un stop attend le retour du moteur. Les boutons Corriger et Vocal sont
     * dans la barre normale, masquée pendant le mode prompt : ce cas ne se présente donc qu'à la sortie
     * d'un mode prompt dont la génération s'arrête (croix, changement de champ).
     */
    fun generationBusy(): Boolean =
        conversation.isGenerating || generationJob?.isActive == true

    /**
     * Touche sur la croix « annuler » : si une génération est en cours, elle est interrompue d'abord
     * (partiel figé dans la bulle et l'historique, décision 11), puis le mode prompt est quitté. La
     * conversation, elle, est conservée (décision 14).
     */
    private fun onCancelRequested() {
        stopGeneration()
        exit()
    }

    /** Croix « annuler » (ou fin de saisie) : retour à la barre normale ; la conversation est conservée (décision 14). */
    fun exit(resync: Boolean = true) {
        if (!active) return
        active = false
        host.cancelVoice() // dictée en cours dans le prompt : annulée, rien ne doit atterrir dans le champ de l'application
        buffer.clear() // la croix « annuler » abandonne le prompt en cours de saisie (pas la conversation)
        host.clearPendingAutocorrection()
        host.cancelDeleteSwipe()
        host.clearSuggestions() // celles du prompt disparaissent ; la barre normale recalcule les siennes
        applyViews()
        if (resync) {
            // La barre normale reprend l'état du champ.
            host.syncAutoCapitalization()
            host.applyState()
            host.updateCorrectionBarVisibility()
        }
    }

    /** Redessine le texte du prompt et son curseur dans la pilule. */
    fun refreshInput() {
        if (this::promptBar.isInitialized) promptBar.setInput(buffer.text, buffer.cursor)
    }

    // ------------------------------------------------------------------
    // Envoi du prompt et réponse progressive (story 5.1, phase 5.1-6)
    // ------------------------------------------------------------------

    /**
     * Bouton d'envoi ou touche Entrée : envoie le prompt au modèle (conversation continue, décision 9) et
     * déploie la zone de chat dès l'envoi (décision 3). Sans effet si le prompt est vide ou si une réponse
     * est déjà en cours (le stop vient en 5.1-7). Sans modèle sélectionné, le prompt reste dans la saisie.
     */
    fun requestSend() {
        if (!active || buffer.isBlank || conversation.isGenerating) return
        if (host.isRecording() || host.isCorrectionInProgress()) { // ne devrait pas arriver : leurs boutons sont masqués en mode prompt
            host.showMessage(context.getString(R.string.ai_action_busy))
            return
        }
        // Après un stop, l'appel au moteur met un court instant à rendre la main : pas de nouvel envoi avant.
        if (generationJob?.isActive == true) return
        val model = host.activeModel()
        if (model == null) {
            host.showMessage(context.getString(R.string.correction_no_model_selected))
            return
        }
        val prompt = buffer.text.trim()
        val history = conversation.history()
        // Story 5.2 : le texte du champ de l'application, joint seulement s'il a changé depuis ce que le modèle
        // a déjà reçu (décision 15), tronqué par le début s'il est trop long (décision 16).
        val capturedText = host.capturedFieldText()
        val prepared = FieldContext.prepare(capturedText)
        val fieldContext = conversation.contextToAttach(prepared)
        // Diagnostic (point ouvert 6.1) : seulement des longueurs, jamais le texte.
        AppLog.i(
            TAG,
            "contexte du champ: capturé=${capturedText?.length ?: 0} car. préparé=${prepared?.length ?: 0} car. " +
                "joint=${fieldContext != null} historique=${history.size} échange(s)",
        )
        if (!conversation.start(prompt, fieldContext, context.getString(R.string.prompt_field_context_header))) return

        // Le dernier mot du prompt est terminé par l'envoi (comme par Entrée dans le champ).
        host.learnFromTyping("\n")
        buffer.clear()
        host.clearPendingAutocorrection()
        chatAvailable = true
        chatShown = true
        chatZone.showMessages(conversation.messages) // prompt de l'utilisateur + bulle de réponse « … »
        // Le préchargement (décision 18) n'est pas fini : la génération attend le moteur, la bulle l'indique.
        if (llmHost.isLoading) chatZone.setLastResponseText(context.getString(R.string.prompt_loading_model))
        refreshInput()
        applyViews()
        host.syncAutoCapitalization() // majuscule en début du prochain prompt
        host.applyState()
        // Ce qui part au modèle : le prompt, précédé du texte du champ s'il y en a un à joindre.
        launchGeneration(model, history, conversation.messages.last().modelPrompt)
    }

    /**
     * Lance la génération : la réponse arrive par morceaux (thread d'arrière-plan, on rebascule sur le
     * thread principal) et s'affiche au fil de l'eau dans la dernière bulle. Le texte du champ de
     * l'application n'est jamais touché (insertion par « Ajouter le texte » : story 5.3).
     */
    private fun launchGeneration(model: AiModel, history: List<ChatExchange>, prompt: String) {
        generationJob = scope.launch {
            try {
                val outcome = generationSession.send(
                    model = model,
                    history = history,
                    prompt = prompt,
                    onLoading = { mainHandler.post { onGenerationLoading() } },
                    onChunk = { chunk -> mainHandler.post { onGenerationChunk(chunk) } },
                )
                // Texte final du moteur ; réponse partielle conservée si la génération a été interrompue.
                // Sans effet si un stop ou la croix ont déjà figé l'échange (5.1-7).
                conversation.finish(outcome.text, outcome.completed)
                conversation.messages.lastOrNull()?.let { chatZone.updateLastResponse(it) }
                applyViews() // le bouton rond redevient « envoyer »
            } catch (e: CancellationException) {
                throw e // fermeture du clavier : la conversation est déjà vidée
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de la génération", t)
                onGenerationFailed(t)
            }
        }
    }

    /**
     * Story 5.3, bouton « Ajouter le texte » sous la réponse de l'échange [index] (décision 7) : insère la
     * réponse à la position du curseur de l'application (voir [Host.insertGeneratedText]), sans rien
     * remplacer. Le mode prompt reste ouvert. Le texte du champ ayant changé, le prompt suivant le
     * renverra au modèle (décision 15).
     */
    private fun onAddTextRequested(index: Int) {
        val message = conversation.messages.getOrNull(index) ?: return
        if (message.status == PromptMessageStatus.IN_PROGRESS) return
        val text = message.response.trim()
        if (text.isEmpty()) return
        if (!host.insertGeneratedText(text)) {
            host.showMessage(context.getString(R.string.prompt_add_text_unavailable))
        }
    }

    /** Le modèle doit être rechargé depuis le disque : la bulle l'indique en attendant le premier morceau. */
    private fun onGenerationLoading() {
        if (!conversation.isGenerating) return
        if (conversation.messages.lastOrNull()?.response.isNullOrEmpty()) {
            chatZone.setLastResponseText(context.getString(R.string.prompt_loading_model))
        }
    }

    /** Un morceau de réponse est arrivé : il s'ajoute à la conversation et à la dernière bulle. */
    private fun onGenerationChunk(chunk: String) {
        if (!conversation.isGenerating) return // morceau en retard (clavier refermé entre-temps)
        conversation.appendResponse(chunk)
        conversation.messages.lastOrNull()?.let { chatZone.updateLastResponse(it) }
    }

    /**
     * Échec du moteur (modèle absent, échec d'inférence). Avec un début de réponse, il est conservé tel
     * quel dans la bulle. Sans rien reçu, l'échange est retiré et le prompt retourne dans la saisie
     * (si elle est vide et que le mode prompt est actif) pour pouvoir être renvoyé.
     */
    private fun onGenerationFailed(t: Throwable) {
        // Échec arrivé après un stop ou la croix : l'échange est déjà figé, rien à signaler.
        if (!conversation.isGenerating) return
        val partial = conversation.messages.lastOrNull()?.response.orEmpty()
        if (partial.isNotBlank()) {
            conversation.interrupt()
            conversation.messages.lastOrNull()?.let { chatZone.updateLastResponse(it) }
        } else {
            val restored = conversation.discardInProgress()
            if (conversation.isEmpty) chatAvailable = false
            chatZone.showMessages(conversation.messages)
            if (restored != null && active && buffer.isBlank) {
                buffer.replaceAll(restored)
                refreshInput()
                host.syncAutoCapitalization()
            }
        }
        applyViews() // le bouton rond redevient « envoyer »
        host.showMessage(context.getString(R.string.generation_error, t.message ?: t.javaClass.simpleName))
    }

    /**
     * Bouton stop ou croix « annuler » pendant une génération (décisions 10 et 11) : l'appel au moteur est
     * interrompu, la réponse partielle déjà affichée est figée dans la bulle et dans l'historique, et le
     * bouton rond redevient « envoyer » sans attendre le retour du moteur. Les morceaux arrivés ensuite
     * sont ignorés (voir [onGenerationChunk]). La conversation est conservée ; la session de génération
     * referme sa conversation LiteRT-LM et la rouvre au prompt suivant en rejouant l'historique.
     * Sans effet s'il n'y a pas de génération en cours.
     */
    fun stopGeneration() {
        if (!conversation.isGenerating) return
        if (generationSessionLazy.isInitialized()) generationSession.stop()
        conversation.interrupt()
        conversation.messages.lastOrNull()?.let { chatZone.updateLastResponse(it) }
        applyViews()
    }

    /**
     * Fermeture du clavier : interrompt la génération, ferme la conversation du moteur et efface la
     * conversation affichée (décisions 9 et 14).
     */
    fun resetConversation() {
        if (generationSessionLazy.isInitialized()) generationSession.reset()
        generationJob?.cancel()
        generationJob = null
        conversation.clear()
        chatAvailable = false
        chatShown = true
        if (this::chatZone.isInitialized) chatZone.clearBubbles()
        applyViews()
    }

    /**
     * Applique l'état du mode prompt aux vues : barre du prompt à la place de la barre du haut, barre
     * d'emojis récents masquée (décision 12), zone de chat selon le bouton afficher/masquer.
     */
    fun applyViews() {
        if (!this::promptBar.isInitialized || !host.barReady()) return
        host.setNormalBarVisible(!active)
        promptBar.visibility = if (active) View.VISIBLE else View.GONE
        if (this::suggestionBar.isInitialized) {
            suggestionBar.visibility = if (active) View.VISIBLE else View.GONE
        }
        host.refreshRecentEmojiBar()
        val chatVisible = active && chatAvailable && chatShown
        if (this::chatZone.isInitialized) chatZone.visibility = if (chatVisible) View.VISIBLE else View.GONE
        promptBar.setChatToggleVisible(active && chatAvailable)
        promptBar.setChatShown(chatVisible)
        promptBar.generating = conversation.isGenerating // bouton rond : stop pendant la génération (décision 10)
    }

    private companion object {
        private const val TAG = "PromptModeController"
    }
}
