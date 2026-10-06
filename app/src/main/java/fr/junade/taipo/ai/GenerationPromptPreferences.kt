package fr.junade.taipo.ai

import android.content.Context

/** Prompt système du mode prompt ([GenerationPrompt.SYSTEM] par défaut), modifiable depuis la page « Prompts système ». */
class GenerationPromptPreferences(context: Context) :
    SystemPromptPreferences(context, KEY_PROMPT, GenerationPrompt.SYSTEM) {

    private companion object {
        const val KEY_PROMPT = "generation_system_prompt"
    }
}
