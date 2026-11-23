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

/** Boutons IA d'une rangée de la barre : actions affichées et bouton IA (qui ouvre le panneau IA). */
internal data class AiRowVisibility(val actions: Set<AiAction>, val aiButton: Boolean)

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
            correction == CorrectionBarState.LOADING ||
            correction == CorrectionBarState.CORRECTING ||
            correction == CorrectionBarState.CANCELLING

    /**
     * État de barre affiché : celui de la zone de suggestions, quelle que soit l'action en cours (correction, écoute,
     * transcription IA). Une action qui démarre depuis la barre normale ne la change donc pas : ses boutons restent à leur
     * place et servent d'arrêt ou d'indicateur (voir [normalRowAi]).
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
     * Actions IA traitées en ce moment : Corriger pendant le chargement, la correction ou l'annulation, Transcription IA pendant
     * l'écoute, le traitement ou l'annulation. Leur bouton est alors affiché même sans être épinglé : c'est lui qui arrête l'action
     * (ou qui montre qu'elle tourne), il ne doit jamais disparaître avant la fin.
     */
    fun busyAiActions(correction: CorrectionBarState, aiTranscription: AiTranscriptionBarState): Set<AiAction> = buildSet {
        if (correction == CorrectionBarState.LOADING ||
            correction == CorrectionBarState.CORRECTING ||
            correction == CorrectionBarState.CANCELLING
        ) {
            add(AiAction.CORRECT)
        }
        if (aiTranscription != AiTranscriptionBarState.IDLE) add(AiAction.TRANSCRIPTION)
    }

    /**
     * Panneau IA (20/11/2026) : boutons IA de la barre normale (champ vide). Le bouton IA est toujours là, juste avant Vocal ; à sa
     * gauche, les actions épinglées ([pins], deux au plus) et celles en cours de traitement ([busyAiActions]). Transcription IA
     * n'est réellement affichée que si le modèle actif la permet (filtré par la vue).
     */
    fun normalRowAi(pins: List<AiAction>, correction: CorrectionBarState, aiTranscription: AiTranscriptionBarState): AiRowVisibility =
        AiRowVisibility(actions = pins.toSet() + busyAiActions(correction, aiTranscription), aiButton = true)

    /**
     * Boutons IA de la rangée de saisie. Menu du robot ouvert : comme la barre normale (épinglées, en cours, bouton IA). Menu
     * fermé : la bande de mots prend la place ; seul Corriger, s'il est épinglé, reste visible tant qu'aucune suggestion n'est
     * affichée (comme avant le panneau), plus les actions en cours de traitement.
     */
    fun typingRowAi(
        pins: List<AiAction>,
        correction: CorrectionBarState,
        zoneState: SuggestionZoneState,
        mode: BarMode,
        wordSuggestionsShown: Boolean,
        aiTranscription: AiTranscriptionBarState,
    ): AiRowVisibility {
        val busy = busyAiActions(correction, aiTranscription)
        if (mode != BarMode.TYPING) return AiRowVisibility(emptySet(), aiButton = false)
        if (zoneState.menuOpen) return AiRowVisibility(pins.toSet() + busy, aiButton = true)
        val hiddenBySuggestion = correction == CorrectionBarState.IDLE && suggestionsDisplayed(zoneState, mode, wordSuggestionsShown)
        val correct = AiAction.CORRECT in pins && correction != CorrectionBarState.HIDDEN && !hiddenBySuggestion
        return AiRowVisibility(if (correct) busy + AiAction.CORRECT else busy, aiButton = false)
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
        CorrectionBarState.CORRECTING -> CorrectAppearance(
            R.string.correction_button_correcting,
            enabled = false,
            alpha = 1f,
            loading = true,
            face = R.color.accent,
        )
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
