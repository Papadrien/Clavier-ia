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

/** Actions IA de la rangée normale (champ vide) : boutons ronds, juste avant Vocal. */
internal data class NormalRowActions(
    val generate: Boolean,
    /** Visible mais grisé et inactif tant que le champ est vide (rien à corriger). */
    val correct: Boolean,
    val aiTranscription: Boolean,
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

    /**
     * Transcription en cours : traitement de la dictée Vocal ([VoiceBarState.TRANSCRIBING]) ou Transcription IA (écoute,
     * traitement ou annulation). Vocal, Corriger et Chat sont alors grisés et inactifs (le moteur et le micro sont pris).
     */
    fun transcriptionInProgress(
        voice: VoiceBarState,
        aiTranscription: AiTranscriptionBarState = AiTranscriptionBarState.IDLE,
    ): Boolean = voice == VoiceBarState.TRANSCRIBING || aiTranscription != AiTranscriptionBarState.IDLE

    /** Chat est grisé et inactif : écoute, transcription, correction ou Transcription IA en cours (le moteur est partagé). */
    fun generateBlocked(
        voice: VoiceBarState,
        correction: CorrectionBarState,
        aiTranscription: AiTranscriptionBarState = AiTranscriptionBarState.IDLE,
    ): Boolean = isBusy(voice, correction) || aiTranscription != AiTranscriptionBarState.IDLE

    /**
     * Transcription IA grisée et inactive pendant une saisie vocale (chargement, écoute, transcription) : le micro est pris.
     * Une Transcription IA déjà en cours reste active (c'est le bouton qui l'arrête).
     */
    fun aiTranscriptionBlocked(voice: VoiceBarState, aiTranscription: AiTranscriptionBarState): Boolean =
        voice != VoiceBarState.IDLE && aiTranscription == AiTranscriptionBarState.IDLE

    /** Écoute, transcription ou correction en cours : les boutons concernés servent d'arrêt ou d'indicateur. */
    fun isBusy(voice: VoiceBarState, correction: CorrectionBarState): Boolean =
        voice != VoiceBarState.IDLE ||
            correction == CorrectionBarState.LOADING || correction == CorrectionBarState.CORRECTING ||
            correction == CorrectionBarState.CANCELLING

    /**
     * État de barre affiché : celui de la zone de suggestions, quelle que soit l'action en cours (correction, écoute,
     * transcription IA). Une action qui démarre depuis la barre normale ne la change donc pas : ses boutons restent à leur
     * place et servent d'arrêt ou d'indicateur (voir [normalRowActions]).
     */
    fun mode(zoneState: SuggestionZoneState): BarMode = zoneState.mode

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
     * ouvert). Chat reste affiché mais grisé et inactif quand [generateBlocked] (le moteur est partagé : pas de génération en parallèle). Corriger reste visible
     * dès que son état n'est plus au repos (chargement, correction en cours) : il sert alors d'annulation ou d'indicateur.
     *
     * Transcription IA reste visible, en petit (icône seule), tant que [aiTranscription] n'est pas au repos (écoute,
     * traitement, annulation), même menu fermé : c'est le bouton qui arrête l'écoute ou annule le traitement, il ne doit
     * jamais disparaître avant la fin. Pendant une saisie vocale ([voice] hors repos), Chat et Transcription IA restent aussi
     * affichés, grisés et inactifs, même menu fermé (la dictée referme le menu en insérant du texte).
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
        // Une saisie vocale (chargement, écoute, transcription) referme le menu en insérant du texte : Chat et Transcription IA
        // restent alors affichés (grisés, icône seule) au lieu de disparaître.
        val voiceActive = voice != VoiceBarState.IDLE
        val hiddenBySuggestion = correction == CorrectionBarState.IDLE &&
            suggestionsDisplayed(zoneState, mode, wordSuggestionsShown)
        return ActionButtonsVisibility(
            voice = true,
            aiTranscription = typing && (menuOpen || aiTranscription != AiTranscriptionBarState.IDLE || voiceActive),
            generate = typing && (menuOpen || voiceActive),
            correct = typing && correction != CorrectionBarState.HIDDEN && !hiddenBySuggestion,
        )
    }

    /**
     * Actions IA de la barre normale : Chat, Corriger et Transcription IA (le bouton n'est réellement affiché que si le
     * modèle actif a une entrée audio). Les trois restent affichés pendant une action (la barre normale ne change pas) ;
     * Chat est alors sans effet, le moteur étant partagé (pas de génération en parallèle : voir [isBusy]).
     */
    fun normalRowActions(mode: BarMode): NormalRowActions {
        val normal = mode == BarMode.NORMAL
        return NormalRowActions(
            generate = normal,
            correct = normal,
            aiTranscription = normal,
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

    /** [transcribing] (voir [transcriptionInProgress]) : au repos, le bouton est grisé et inactif. */
    fun correctAppearance(state: CorrectionBarState, transcribing: Boolean = false): CorrectAppearance? = when (state) {
        CorrectionBarState.HIDDEN -> null
        CorrectionBarState.IDLE ->
            if (transcribing) {
                CorrectAppearance(R.string.correction_button_idle, enabled = false, alpha = RESTRICTED_ALPHA)
            } else {
                CorrectAppearance(R.string.correction_button_idle, enabled = true, alpha = 1f)
            }
        // Chargement ou correction en cours : un nouvel appui sur le bouton annule la correction.
        CorrectionBarState.LOADING -> CorrectAppearance(R.string.correction_button_loading, enabled = true, alpha = 1f, loading = true)
        // Traitement en cours : fond violet, opacité pleine (le bouton reste désactivé).
        CorrectionBarState.CORRECTING -> CorrectAppearance(R.string.correction_button_correcting, enabled = false, alpha = 1f, loading = true, face = R.color.accent)
        CorrectionBarState.CANCELLING -> CorrectAppearance(R.string.correction_button_cancelling, enabled = false, alpha = 0.6f, loading = true)
    }

    /** [aiTranscription] : une Transcription IA est en cours, Vocal est grisé (le micro est pris). */
    fun voiceAppearance(
        state: VoiceBarState,
        aiTranscription: AiTranscriptionBarState = AiTranscriptionBarState.IDLE,
    ): VoiceAppearance = when (state) {
        VoiceBarState.IDLE -> VoiceAppearance(
            R.string.voice_button_idle,
            R.color.surface_button,
            if (aiTranscription != AiTranscriptionBarState.IDLE) RESTRICTED_ALPHA else 1f,
        )
        VoiceBarState.LOADING -> VoiceAppearance(R.string.voice_button_loading, R.color.surface_button, 0.6f, loading = true)
        VoiceBarState.RECORDING -> VoiceAppearance(R.string.voice_button_recording, R.color.action_danger, 1f)
        // Transcription en cours de traitement : fond violet, grisé (un nouvel appui est ignoré jusqu'à la fin).
        VoiceBarState.TRANSCRIBING -> VoiceAppearance(R.string.voice_button_transcribing, R.color.accent, RESTRICTED_ALPHA)
    }

    /** [voice] : une saisie vocale est en cours, le bouton au repos est grisé (voir [aiTranscriptionBlocked]). */
    fun aiTranscriptionAppearance(
        state: AiTranscriptionBarState,
        voice: VoiceBarState = VoiceBarState.IDLE,
    ): AiTranscriptionAppearance = when (state) {
        AiTranscriptionBarState.IDLE -> AiTranscriptionAppearance(
            R.color.surface_button,
            if (aiTranscriptionBlocked(voice, state)) RESTRICTED_ALPHA else 1f,
        )
        AiTranscriptionBarState.RECORDING -> AiTranscriptionAppearance(R.color.action_danger, 1f)
        // Traitement par le modèle : fond violet, opacité pleine. L'annulation (arrêt natif en cours) reste grisée.
        AiTranscriptionBarState.PROCESSING -> AiTranscriptionAppearance(R.color.accent, 1f)
        AiTranscriptionBarState.CANCELLING -> AiTranscriptionAppearance(R.color.surface_button, 0.6f)
    }
}
