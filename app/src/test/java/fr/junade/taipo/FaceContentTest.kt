package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lot T6 : le contenu posé sur une face violette ou rouge est blanc ; sur une face grise, il suit le thème. */
class FaceContentTest {

    @Test
    fun `une face violette ou rouge porte du contenu blanc`() {
        val colored = listOf(
            R.color.accent,
            R.color.action_send,
            R.color.action_danger,
            R.color.taipo_purple,
            R.color.key_enter,
            R.color.key_popup_selection,
            R.color.clip_pin_badge,
            R.color.clip_action_primary,
            R.color.emoji_tab_selected,
            R.color.button_primary,
        )
        for (face in colored) {
            assertTrue(FaceContent.isColored(face), "face $face")
            assertEquals(R.color.text_on_accent, FaceContent.colorOn(face), "face $face")
        }
    }

    @Test
    fun `une face grise garde la couleur du texte du theme`() {
        val grey = listOf(
            R.color.surface_button,
            R.color.surface_pill,
            R.color.key_normal,
            R.color.key_functional,
            R.color.clip_item,
            R.color.clip_dialog,
            R.color.button_secondary,
        )
        for (face in grey) {
            assertFalse(FaceContent.isColored(face), "face $face")
            assertEquals(R.color.text_primary, FaceContent.colorOn(face), "face $face")
        }
    }

    @Test
    fun `les faces des etats violets de la barre portent du contenu blanc`() {
        // Corriger en cours, Transcription en cours, Vocal en écoute : voir BarVisibility.
        assertEquals(R.color.text_on_accent, FaceContent.colorOn(BarVisibility.correctAppearance(CorrectionBarState.CORRECTING)!!.face))
        assertEquals(R.color.text_on_accent, FaceContent.colorOn(BarVisibility.voiceAppearance(VoiceBarState.TRANSCRIBING).background))
        assertEquals(R.color.text_on_accent, FaceContent.colorOn(BarVisibility.voiceAppearance(VoiceBarState.RECORDING).background))
        assertEquals(R.color.text_on_accent, FaceContent.colorOn(BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.PROCESSING).face))
        assertEquals(R.color.text_on_accent, FaceContent.colorOn(BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.RECORDING).face))
    }

    @Test
    fun `les faces au repos de la barre gardent la couleur du texte`() {
        assertEquals(R.color.text_primary, FaceContent.colorOn(BarVisibility.voiceAppearance(VoiceBarState.IDLE).background))
        assertEquals(R.color.text_primary, FaceContent.colorOn(BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.IDLE).face))
        assertEquals(R.color.text_primary, FaceContent.colorOn(BarVisibility.correctAppearance(CorrectionBarState.IDLE)!!.face))
    }
}
