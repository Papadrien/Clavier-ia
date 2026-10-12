package fr.junade.taipo.ai

import android.content.Context

/** Prompt système de la transcription IA ([TranscriptionPrompt.SYSTEM] par défaut), modifiable depuis la page « Prompts système ». */
class TranscriptionPromptPreferences(context: Context) :
    SystemPromptPreferences(context, KEY_PROMPT, TranscriptionPrompt.SYSTEM) {

    private companion object {
        const val KEY_PROMPT = "transcription_system_prompt"
    }
}
