package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Règles d'affichage de la barre du haut à deux états (lot 4.2, refonte « normale / saisie »). */
class BarVisibilityTest {

    private fun typing(): SuggestionZoneState = SuggestionZoneState().apply { onFieldTextChanged(true) }

    private fun typingMenuOpen(): SuggestionZoneState = typing().apply { toggleMenu() }

    /** Boutons de la rangée de saisie (Vocal y est toujours, fixé à droite). */
    private data class Buttons(val voice: Boolean, val generate: Boolean, val correct: Boolean, val aiTranscription: Boolean, val aiButton: Boolean)

    /**
     * Rangée de saisie avec les trois actions d'avant le panneau IA épinglées (Chat, Corriger, Transcription) : les règles d'avant
     * restent vraies pour elles. [voice] ne change pas les boutons affichés (seulement leur apparence, testée à part).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun actions(
        zone: SuggestionZoneState,
        correction: CorrectionBarState = CorrectionBarState.IDLE,
        voice: VoiceBarState = VoiceBarState.IDLE,
        wordsShown: Boolean = false,
        aiTranscription: AiTranscriptionBarState = AiTranscriptionBarState.IDLE,
        pins: List<AiAction> = listOf(AiAction.CORRECT, AiAction.CHAT, AiAction.TRANSCRIPTION),
    ): Buttons {
        val mode = BarVisibility.mode(zone)
        val row = BarVisibility.typingRowAi(pins, correction, zone, mode, wordsShown, aiTranscription)
        return Buttons(
            voice = true,
            generate = AiAction.CHAT in row.actions,
            correct = AiAction.CORRECT in row.actions,
            aiTranscription = AiAction.TRANSCRIPTION in row.actions,
            aiButton = row.aiButton,
        )
    }

    private fun content(zone: SuggestionZoneState, correction: CorrectionBarState = CorrectionBarState.IDLE): BarContentVisibility =
        BarVisibility.content(zone, BarVisibility.mode(zone))

    @Test
    fun `occupe pendant une ecoute une transcription ou une correction, pas au repos`() {
        assertFalse(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.IDLE))
        assertFalse(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.HIDDEN))
        assertTrue(BarVisibility.isBusy(VoiceBarState.RECORDING, CorrectionBarState.IDLE))
        assertTrue(BarVisibility.isBusy(VoiceBarState.LOADING, CorrectionBarState.IDLE))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.LOADING))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.CORRECTING))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.CANCELLING))
    }

    @Test
    fun `barre normale montre sa rangee avec Parametres Smart Clipboard Annuler les actions IA et Vocal`() {
        val zone = SuggestionZoneState()
        val shown = content(zone, CorrectionBarState.HIDDEN)
        assertTrue(shown.normalRow)
        assertFalse(shown.typingRow)
        assertFalse(shown.robot)
        assertFalse(shown.words)
        assertFalse(shown.menuUtilityButtons)
        val buttons = actions(zone, CorrectionBarState.HIDDEN)
        assertTrue(buttons.voice)
        assertFalse(buttons.correct)
        assertFalse(buttons.generate)
        assertFalse(buttons.aiTranscription)
        // Les actions IA de la barre normale sont des boutons à part (jeu propre à la rangée normale) : les épinglées et le bouton IA.
        val normal = BarVisibility.normalRowAi(AiPins.DEFAULT, CorrectionBarState.HIDDEN, AiTranscriptionBarState.IDLE)
        assertEquals(setOf(AiAction.CORRECT, AiAction.TRANSCRIPTION), normal.actions)
        assertTrue(normal.aiButton)
    }

    @Test
    fun `la barre normale montre les actions epinglees, et celles en cours meme non epinglees`() {
        val pins = listOf(AiAction.TONE, AiAction.TRANSLATE)
        val idle = BarVisibility.normalRowAi(pins, CorrectionBarState.HIDDEN, AiTranscriptionBarState.IDLE)
        assertEquals(setOf(AiAction.TONE, AiAction.TRANSLATE), idle.actions)
        // Correction lancée depuis le panneau : Corriger apparaît le temps du traitement (c'est lui qui l'annule).
        val correcting = BarVisibility.normalRowAi(pins, CorrectionBarState.LOADING, AiTranscriptionBarState.IDLE)
        assertTrue(AiAction.CORRECT in correcting.actions)
        val recording = BarVisibility.normalRowAi(emptyList(), CorrectionBarState.HIDDEN, AiTranscriptionBarState.RECORDING)
        assertEquals(setOf(AiAction.TRANSCRIPTION), recording.actions)
        assertTrue(recording.aiButton)
    }

    @Test
    fun `rangee de saisie menu ferme seul Corriger epingle reste, et le bouton IA est derriere le robot`() {
        val zone = typing()
        assertTrue(actions(zone, pins = AiPins.DEFAULT).correct)
        assertFalse(actions(zone, pins = AiPins.DEFAULT).aiButton)
        // Corriger non épinglé : rien à droite de la bande, sauf pendant une correction.
        assertFalse(actions(zone, pins = listOf(AiAction.TONE)).correct)
        assertTrue(actions(zone, CorrectionBarState.CORRECTING, pins = listOf(AiAction.TONE)).correct)
        // Menu ouvert : épinglées et bouton IA.
        val open = BarVisibility.typingRowAi(
            listOf(AiAction.TONE),
            CorrectionBarState.IDLE,
            typingMenuOpen(),
            BarMode.TYPING,
            false,
            AiTranscriptionBarState.IDLE,
        )
        assertEquals(setOf(AiAction.TONE), open.actions)
        assertTrue(open.aiButton)
    }

    @Test
    fun `la puce de collage est dans la rangee normale a cote du bouton Smart Clipboard`() {
        val zone = SuggestionZoneState().apply { setPasteAvailable(true) }
        assertTrue(content(zone, CorrectionBarState.HIDDEN).pasteChip)
        // Pendant la saisie, la puce n'est plus affichée (elle revient quand le champ est vide).
        zone.onFieldTextChanged(true)
        assertFalse(content(zone).pasteChip)
    }

    @Test
    fun `saisie menu ferme montre le robot et les mots, Vocal et Corriger`() {
        val zone = typing()
        val shown = content(zone)
        assertTrue(shown.typingRow)
        assertFalse(shown.normalRow)
        assertTrue(shown.robot)
        assertTrue(shown.words)
        assertFalse(shown.inline)
        assertFalse(shown.menuUtilityButtons)
        val buttons = actions(zone)
        assertTrue(buttons.voice)
        assertTrue(buttons.correct)
        assertFalse(buttons.generate)
        assertFalse(buttons.aiTranscription)
    }

    @Test
    fun `menu ouvert la bande est effacee et la rangee devient Robot Parametres Clipboard Annuler Chat Corriger Transcription`() {
        val zone = typingMenuOpen()
        val shown = content(zone)
        assertTrue(shown.robot)
        assertFalse(shown.words)
        assertTrue(shown.menuUtilityButtons)
        val buttons = actions(zone)
        assertTrue(buttons.voice)
        assertTrue(buttons.generate)
        assertTrue(buttons.correct)
        assertTrue(buttons.aiTranscription)
    }

    @Test
    fun `Corriger reste cache tant que son etat est HIDDEN`() {
        assertFalse(actions(typing(), CorrectionBarState.HIDDEN).correct)
    }

    @Test
    fun `Corriger se cache tant qu une suggestion de mots est affichee et revient sans suggestion`() {
        val zone = typing()
        assertFalse(actions(zone, wordsShown = true).correct)
        assertTrue(actions(zone, wordsShown = false).correct)
    }

    @Test
    fun `Corriger reste visible hors du repos meme avec une suggestion affichee`() {
        val zone = typing()
        for (state in listOf(CorrectionBarState.LOADING, CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertTrue(actions(zone, state, wordsShown = true).correct, "état $state")
        }
    }

    @Test
    fun `menu ouvert Corriger est visible meme avec des mots proposes`() {
        assertTrue(actions(typingMenuOpen(), wordsShown = true).correct)
    }

    @Test
    fun `les suggestions d auto-remplissage cachent Corriger et remplacent la bande`() {
        val zone = typing().apply { setInlineAvailable(true) }
        val shown = content(zone)
        assertTrue(shown.inline)
        assertFalse(shown.words)
        assertFalse(actions(zone).correct)
    }

    @Test
    fun `l auto-remplissage sur un champ vide donne la rangee de saisie`() {
        val zone = SuggestionZoneState().apply { setInlineAvailable(true) }
        val shown = content(zone, CorrectionBarState.HIDDEN)
        assertTrue(shown.typingRow)
        assertTrue(shown.inline)
        assertTrue(shown.robot)
    }

    @Test
    fun `suggestionsDisplayed ne compte ni la barre normale ni le menu ouvert`() {
        val normal = SuggestionZoneState()
        assertFalse(BarVisibility.suggestionsDisplayed(normal, BarMode.NORMAL, wordSuggestionsShown = true))
        val menu = typingMenuOpen()
        assertFalse(BarVisibility.suggestionsDisplayed(menu, BarMode.TYPING, wordSuggestionsShown = true))
        assertTrue(BarVisibility.suggestionsDisplayed(typing(), BarMode.TYPING, wordSuggestionsShown = true))
        assertFalse(BarVisibility.suggestionsDisplayed(typing(), BarMode.TYPING, wordSuggestionsShown = false))
    }

    @Test
    fun `pendant l ecoute et le traitement d une transcription IA le bouton reste visible menu ferme`() {
        val zone = typing()
        val processing = actions(zone, aiTranscription = AiTranscriptionBarState.PROCESSING)
        assertTrue(processing.aiTranscription)
        assertFalse(processing.generate) // seul Transcription IA reste hors du menu : Chat reste derrière
        // L'écoute aussi : c'est le bouton qui l'arrête.
        assertTrue(actions(zone, aiTranscription = AiTranscriptionBarState.RECORDING).aiTranscription)
        assertTrue(actions(zone, aiTranscription = AiTranscriptionBarState.CANCELLING).aiTranscription)
        assertFalse(actions(zone).aiTranscription)
    }

    @Test
    fun `pendant une ecoute Chat reste affiche (grise) et le menu reste`() {
        val zone = typingMenuOpen()
        val buttons = actions(zone, voice = VoiceBarState.RECORDING)
        assertTrue(buttons.voice)
        assertTrue(buttons.generate)
        assertTrue(buttons.aiTranscription)
        assertTrue(content(zone).robot)
    }

    @Test
    fun `pendant une saisie vocale menu ferme Chat et Transcription IA ne sont pas affiches`() {
        val zone = typing() // menu refermé par le texte dicté
        for (voice in listOf(VoiceBarState.LOADING, VoiceBarState.RECORDING, VoiceBarState.TRANSCRIBING)) {
            val buttons = actions(zone, voice = voice)
            assertFalse(buttons.generate, "Chat masqué pendant $voice")
            assertFalse(buttons.aiTranscription, "Transcription IA masquée pendant $voice")
            assertTrue(BarVisibility.generateBlocked(voice, CorrectionBarState.IDLE), "Chat grisé pendant $voice")
            assertTrue(BarVisibility.aiTranscriptionBlocked(voice, AiTranscriptionBarState.IDLE), "Transcription IA grisée pendant $voice")
        }
        // Au repos, menu fermé : toujours rangés derrière le robot.
        val idle = actions(zone)
        assertFalse(idle.generate)
        assertFalse(idle.aiTranscription)
    }

    @Test
    fun `une action en cours ne change pas la barre sur un champ vide`() {
        val zone = SuggestionZoneState()
        assertEquals(BarMode.NORMAL, BarVisibility.mode(zone))
    }

    @Test
    fun `panneau Smart Clipboard ouvert la barre reste normale`() {
        val zone = SuggestionZoneState().apply { setClipboardPanelOpen(true) }
        assertEquals(BarMode.NORMAL, BarVisibility.mode(zone))
    }

    @Test
    fun `les actions IA passent en pilules seulement quand le menu est ouvert`() {
        assertTrue(BarVisibility.labeledActions(typingMenuOpen(), BarMode.TYPING))
        assertFalse(BarVisibility.labeledActions(typing(), BarMode.TYPING))
        assertFalse(BarVisibility.labeledActions(SuggestionZoneState(), BarMode.NORMAL))
    }

    @Test
    fun `apparence de Corriger selon l etat`() {
        assertNull(BarVisibility.correctAppearance(CorrectionBarState.HIDDEN))
        val idle = BarVisibility.correctAppearance(CorrectionBarState.IDLE)!!
        assertEquals(R.string.correction_button_idle, idle.label)
        assertTrue(idle.enabled)
        assertEquals(1f, idle.alpha)
        // Chargement / correction en cours : le bouton reste actif, un nouvel appui annule.
        val loading = BarVisibility.correctAppearance(CorrectionBarState.LOADING)!!
        assertTrue(loading.enabled)
        assertEquals(1f, loading.alpha)
        for (state in listOf(CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertFalse(BarVisibility.correctAppearance(state)!!.enabled)
        }
        assertEquals(1f, BarVisibility.correctAppearance(CorrectionBarState.CORRECTING)!!.alpha)
        assertEquals(0.6f, BarVisibility.correctAppearance(CorrectionBarState.CANCELLING)!!.alpha)
        for (state in listOf(CorrectionBarState.LOADING, CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertTrue(BarVisibility.correctAppearance(state)!!.loading, "une roue remplace l'icône pendant $state")
        }
        assertFalse(BarVisibility.correctAppearance(CorrectionBarState.IDLE)!!.loading)
    }

    @Test
    fun `Corriger a le fond violet pendant le traitement de la correction seulement`() {
        assertEquals(R.color.accent, BarVisibility.correctAppearance(CorrectionBarState.CORRECTING)!!.face)
        for (state in listOf(CorrectionBarState.IDLE, CorrectionBarState.LOADING, CorrectionBarState.CANCELLING)) {
            assertEquals(R.color.surface_button, BarVisibility.correctAppearance(state)!!.face, "fond gris pendant $state")
        }
    }

    @Test
    fun `Transcription IA a le fond violet pendant le traitement seulement`() {
        val processing = BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.PROCESSING)
        assertEquals(R.color.accent, processing.face)
        assertEquals(1f, processing.alpha)
        assertEquals(R.color.action_danger, BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.RECORDING).face)
        assertEquals(R.color.surface_button, BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.IDLE).face)
        val cancelling = BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.CANCELLING)
        assertEquals(R.color.surface_button, cancelling.face)
        assertEquals(0.6f, cancelling.alpha)
    }

    @Test
    fun `apparence de Vocal selon l etat, rouge a l ecoute et violet a la transcription`() {
        val recording = BarVisibility.voiceAppearance(VoiceBarState.RECORDING)
        assertEquals(R.color.action_danger, recording.background)
        assertEquals(1f, recording.alpha)
        assertEquals(R.string.voice_button_recording, recording.label)
        for (state in listOf(VoiceBarState.IDLE, VoiceBarState.LOADING)) {
            assertEquals(R.color.surface_button, BarVisibility.voiceAppearance(state).background)
        }
        // Transcription en cours de traitement : fond violet.
        assertEquals(R.color.accent, BarVisibility.voiceAppearance(VoiceBarState.TRANSCRIBING).background)
        // Seul le chargement du modèle remplace l'icône micro par une roue animée.
        assertEquals(true, BarVisibility.voiceAppearance(VoiceBarState.LOADING).loading)
        listOf(VoiceBarState.IDLE, VoiceBarState.RECORDING, VoiceBarState.TRANSCRIBING).forEach { state ->
            assertEquals(false, BarVisibility.voiceAppearance(state).loading)
        }
        assertEquals(1f, BarVisibility.voiceAppearance(VoiceBarState.IDLE).alpha)
        assertEquals(0.6f, BarVisibility.voiceAppearance(VoiceBarState.LOADING).alpha)
        // Transcription en cours : le bouton est grisé (un nouvel appui est ignoré).
        assertEquals(RESTRICTED_ALPHA, BarVisibility.voiceAppearance(VoiceBarState.TRANSCRIBING).alpha)
    }

    @Test
    fun `Chat est grise pendant une ecoute, une transcription, une correction ou une transcription IA`() {
        assertFalse(BarVisibility.generateBlocked(VoiceBarState.IDLE, CorrectionBarState.IDLE))
        for (voice in listOf(VoiceBarState.LOADING, VoiceBarState.RECORDING, VoiceBarState.TRANSCRIBING)) {
            assertTrue(BarVisibility.generateBlocked(voice, CorrectionBarState.IDLE), "grisé pendant $voice")
        }
        for (correction in listOf(CorrectionBarState.LOADING, CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertTrue(BarVisibility.generateBlocked(VoiceBarState.IDLE, correction), "grisé pendant $correction")
        }
        assertTrue(
            BarVisibility.generateBlocked(VoiceBarState.IDLE, CorrectionBarState.IDLE, AiTranscriptionBarState.PROCESSING),
        )
    }

    @Test
    fun `Transcription IA est grisee pendant une saisie vocale mais reste active pendant sa propre action`() {
        for (voice in listOf(VoiceBarState.LOADING, VoiceBarState.RECORDING, VoiceBarState.TRANSCRIBING)) {
            assertTrue(BarVisibility.aiTranscriptionBlocked(voice, AiTranscriptionBarState.IDLE), "bloquée pendant $voice")
            assertEquals(RESTRICTED_ALPHA, BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.IDLE, voice).alpha)
        }
        assertFalse(BarVisibility.aiTranscriptionBlocked(VoiceBarState.IDLE, AiTranscriptionBarState.IDLE))
        assertEquals(1f, BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.IDLE).alpha)
        // Une Transcription IA en cours reste active : c'est le bouton qui l'arrête ou l'annule.
        assertFalse(BarVisibility.aiTranscriptionBlocked(VoiceBarState.IDLE, AiTranscriptionBarState.RECORDING))
    }

    @Test
    fun `transcription en cours pour Vocal et pour Transcription IA`() {
        assertFalse(BarVisibility.transcriptionInProgress(VoiceBarState.IDLE))
        for (state in listOf(VoiceBarState.LOADING, VoiceBarState.RECORDING)) {
            assertFalse(BarVisibility.transcriptionInProgress(state), "pas de transcription pendant $state")
        }
        assertTrue(BarVisibility.transcriptionInProgress(VoiceBarState.TRANSCRIBING))
        for (state in listOf(AiTranscriptionBarState.RECORDING, AiTranscriptionBarState.PROCESSING, AiTranscriptionBarState.CANCELLING)) {
            assertTrue(BarVisibility.transcriptionInProgress(VoiceBarState.IDLE, state), "transcription IA $state")
        }
        assertFalse(BarVisibility.transcriptionInProgress(VoiceBarState.IDLE, AiTranscriptionBarState.IDLE))
    }

    @Test
    fun `Vocal est grise pendant une transcription IA`() {
        for (state in listOf(AiTranscriptionBarState.RECORDING, AiTranscriptionBarState.PROCESSING, AiTranscriptionBarState.CANCELLING)) {
            assertEquals(RESTRICTED_ALPHA, BarVisibility.voiceAppearance(VoiceBarState.IDLE, state).alpha, "grisé pendant $state")
        }
        assertEquals(1f, BarVisibility.voiceAppearance(VoiceBarState.IDLE, AiTranscriptionBarState.IDLE).alpha)
    }

    @Test
    fun `Corriger est grise et inactif pendant une transcription`() {
        val idle = BarVisibility.correctAppearance(CorrectionBarState.IDLE, transcribing = true)!!
        assertFalse(idle.enabled)
        assertEquals(RESTRICTED_ALPHA, idle.alpha)
        assertEquals(R.string.correction_button_idle, idle.label)
        // Sans transcription, l'apparence de repos est inchangée.
        val normal = BarVisibility.correctAppearance(CorrectionBarState.IDLE, transcribing = false)!!
        assertTrue(normal.enabled)
        assertEquals(1f, normal.alpha)
        // Champ vide : rien à corriger, aucune apparence (la barre normale le grise elle-même).
        assertNull(BarVisibility.correctAppearance(CorrectionBarState.HIDDEN, transcribing = true))
    }
}
