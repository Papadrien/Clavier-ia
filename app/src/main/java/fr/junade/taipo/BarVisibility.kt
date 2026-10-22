package fr.junade.taipo

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/**
 * Règles d'affichage de la barre du haut (lot 4.2) : quels boutons et quelle zone sont visibles, et
 * l'apparence des boutons Corriger et Vocal selon leur état. Logique pure (sans vue), extraite de
 * [CorrectionBarView] sans changement de comportement, testée en JVM.
 */
/** Story 10.1 : opacité d'un bouton grisé parce que le champ est sensible (il reste touchable pour afficher la raison). */
internal const val RESTRICTED_ALPHA = 0.4f

internal data class ActionButtonsVisibility(
    val voice: Boolean,
    /** Transcription IA : rangée du menu ouvert, ou petite pendant son traitement (menu fermé). */
    val aiTranscription: Boolean,
    val generate: Boolean,
    val correct: Boolean,
)

/** Ce que montre la barre : quelle rangée, et le contenu variable de la rangée de saisie et de la rangée normale. */
internal data class BarContentVisibility(
    val normalRow: Boolean,
    val typingRow: Boolean,
    /** Robot (menu de gauche) : ancré à gauche de la rangée de saisie. */
    val robot: Boolean,
    val words: Boolean,
    val inline: Boolean,
    /** Puce de collage : rangée normale, à côté du bouton Smart Clipboard. */
    val pasteChip: Boolean,
    /** Paramètres, Smart Clipboard et Annuler de la rangée de saisie : seulement menu ouvert. */
    val menuUtilityButtons: Boolean,
)

/** Apparence du bouton Corriger (état [CorrectionBarState.HIDDEN] : aucun changement d'apparence). */
internal data class CorrectAppearance(
    @StringRes val label: Int,
    val enabled: Boolean,
    val alpha: Float,
    /** Chargement du modèle ou correction en cours : une roue animée remplace l'icône du bouton. */
    val loading: Boolean = false,
    /** Couleur de la face : violet pendant le traitement de la correction, gris sinon. */
    @ColorRes val face: Int = R.color.surface_button,
)

/** Apparence du bouton Transcription IA : couleur de la face (rouge à l'écoute, violet au traitement, gris sinon) et opacité. */
internal data class AiTranscriptionAppearance(
    @ColorRes val face: Int,
    val alpha: Float,
)

/** Apparence du bouton Vocal. */
internal data class VoiceAppearance(
    @StringRes val label: Int,
    @ColorRes val background: Int,
    val alpha: Float,
    /** Le modèle se charge (le contour de la capsule du micro se dessine alors en boucle ; voir [MicIconMode]). */
    val loading: Boolean = false,
)

internal object BarVisibility {

    /** Écoute, transcription ou correction en cours : les boutons concernés servent d'arrêt ou d'indicateur. */
    fun isBusy(voice: VoiceBarState, correction: CorrectionBarState): Boolean =
        voice != VoiceBarState.IDLE ||
            correction == CorrectionBarState.LOADING || correction == CorrectionBarState.CORRECTING ||
            correction == CorrectionBarState.CANCELLING

    /**
     * État de barre affiché. Une correction ou une transcription IA en cours rend la barre de saisie même si le champ est
     * vide (leur bouton sert d'arrêt ou d'indicateur et doit rester visible), sauf panneau Smart Clipboard ouvert : la
     * barre ne change alors pas.
     */
    fun mode(
        zoneState: SuggestionZoneState,
        correction: CorrectionBarState,
        aiTranscription: AiTranscriptionBarState,
    ): BarMode {
        if (zoneState.mode == BarMode.TYPING || zoneState.clipboardPanelOpen) return zoneState.mode
        val working = isBusy(VoiceBarState.IDLE, correction) || aiTranscription != AiTranscriptionBarState.IDLE
        return if (working) BarMode.TYPING else BarMode.NORMAL
    }

    fun content(zoneState: SuggestionZoneState, mode: BarMode): BarContentVisibility {
        val typing = mode == BarMode.TYPING
        val menuOpen = typing && zoneState.menuOpen
        return BarContentVisibility(
            normalRow = !typing,
            typingRow = typing,
            robot = typing,
            words = typing && !menuOpen && !zoneState.inlineAvailable,
            inline = typing && !menuOpen && zoneState.inlineAvailable,
            pasteChip = !typing && zoneState.pasteAvailable,
            menuUtilityButtons = menuOpen,
        )
    }

    /**
     * Vocal est toujours visible, fixé à droite. Dans la rangée de saisie : Corriger est visible dès que du texte permet de
     * corriger, sauf tant qu'une suggestion est affichée ; Transcription IA et Chat sont rangés derrière le robot (menu
     * ouvert). Chat disparaît quand [isBusy] (le moteur est partagé : pas de génération en parallèle). Corriger reste visible
     * dès que son état n'est plus au repos (chargement, correction en cours) : il sert alors d'annulation ou d'indicateur.
     *
     * Transcription IA reste visible, en petit (icône seule), tant que [aiTranscription] vaut
     * [AiTranscriptionBarState.PROCESSING], même menu fermé : l'utilisateur voit que la transcription tourne encore.
     * [wordSuggestionsShown] : la bande de mots affiche au moins un mot ou un emoji.
     */
    fun actionButtons(
        voice: VoiceBarState,
        correction: CorrectionBarState,
        zoneState: SuggestionZoneState,
        mode: BarMode,
        wordSuggestionsShown: Boolean = false,
        aiTranscription: AiTranscriptionBarState = AiTranscriptionBarState.IDLE,
    ): ActionButtonsVisibility {
        val typing = mode == BarMode.TYPING
        val menuOpen = typing && zoneState.menuOpen
        val busy = isBusy(voice, correction)
        val hiddenBySuggestion = correction == CorrectionBarState.IDLE &&
            suggestionsDisplayed(zoneState, mode, wordSuggestionsShown)
        return ActionButtonsVisibility(
            voice = true,
            aiTranscription = typing && (menuOpen || aiTranscription == AiTranscriptionBarState.PROCESSING),
            generate = menuOpen && !busy,
            correct = typing && correction != CorrectionBarState.HIDDEN && !hiddenBySuggestion,
        )
    }

    /**
     * Une suggestion est affichée dans la rangée de saisie : mots ou emoji de la bande ([wordSuggestionsShown]) ou
     * suggestions d'auto-remplissage en ligne. Ni la barre normale ni le menu ouvert (la bande est alors effacée) ne comptent.
     */
    fun suggestionsDisplayed(
        zoneState: SuggestionZoneState,
        mode: BarMode,
        wordSuggestionsShown: Boolean,
    ): Boolean = mode == BarMode.TYPING && !zoneState.menuOpen && (zoneState.inlineAvailable || wordSuggestionsShown)

    /** Menu ouvert : Chat, Corriger et Transcription passent en pilules icône + texte, si la rangée tient (voir CorrectionBarView). */
    fun labeledActions(zoneState: SuggestionZoneState, mode: BarMode): Boolean =
        mode == BarMode.TYPING && zoneState.menuOpen

    fun correctAppearance(state: CorrectionBarState): CorrectAppearance? = when (state) {
        CorrectionBarState.HIDDEN -> null
        CorrectionBarState.IDLE -> CorrectAppearance(R.string.correction_button_idle, enabled = true, alpha = 1f)
        // Chargement ou correction en cours : un nouvel appui sur le bouton annule la correction.
        CorrectionBarState.LOADING -> CorrectAppearance(R.string.correction_button_loading, enabled = true, alpha = 1f, loading = true)
        // Traitement en cours : fond violet, opacité pleine (le bouton reste désactivé).
        CorrectionBarState.CORRECTING -> CorrectAppearance(R.string.correction_button_correcting, enabled = false, alpha = 1f, loading = true, face = R.color.accent)
        CorrectionBarState.CANCELLING -> CorrectAppearance(R.string.correction_button_cancelling, enabled = false, alpha = 0.6f, loading = true)
    }

    fun voiceAppearance(state: VoiceBarState): VoiceAppearance = when (state) {
        VoiceBarState.IDLE -> VoiceAppearance(R.string.voice_button_idle, R.color.surface_button, 1f)
        VoiceBarState.LOADING -> VoiceAppearance(R.string.voice_button_loading, R.color.surface_button, 0.6f, loading = true)
        VoiceBarState.RECORDING -> VoiceAppearance(R.string.voice_button_recording, R.color.action_danger, 1f)
        // Transcription en cours de traitement : fond violet, opacité pleine.
        VoiceBarState.TRANSCRIBING -> VoiceAppearance(R.string.voice_button_transcribing, R.color.accent, 1f)
    }

    fun aiTranscriptionAppearance(state: AiTranscriptionBarState): AiTranscriptionAppearance = when (state) {
        AiTranscriptionBarState.IDLE -> AiTranscriptionAppearance(R.color.surface_button, 1f)
        AiTranscriptionBarState.RECORDING -> AiTranscriptionAppearance(R.color.action_danger, 1f)
        // Traitement par le modèle : fond violet, opacité pleine. L'annulation (arrêt natif en cours) reste grisée.
        AiTranscriptionBarState.PROCESSING -> AiTranscriptionAppearance(R.color.accent, 1f)
        AiTranscriptionBarState.CANCELLING -> AiTranscriptionAppearance(R.color.surface_button, 0.6f)
    }
}
