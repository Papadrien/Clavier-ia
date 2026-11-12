package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import fr.junade.taipo.ai.CorrectionPromptPreferences
import fr.junade.taipo.ai.GenerationPromptPreferences
import fr.junade.taipo.ai.TranscriptionPromptPreferences

/**
 * Page « Prompts système » : consulter, modifier et réinitialiser les prompts système envoyés au modèle IA, un par
 * usage (correction, mode prompt, transcription IA). Chaque champ affiche le prompt actuellement utilisé (le défaut tant qu'aucun prompt
 * personnalisé n'est enregistré).
 */
class SystemPromptsActivity : Activity() {

    // Écrans de l'application : même thème que le clavier (page « Apparence »), voir KeyboardTheme.wrap.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pass premium : la page n'est ouverte qu'avec le pass (la ligne de l'accueil est verrouillée sinon, voir MainActivity).
        setContentView(R.layout.activity_system_prompts)
        val container = findViewById<LinearLayout>(R.id.container_system_prompts)

        addSystemPromptSection(
            container,
            CorrectionPromptPreferences(this),
            R.id.edit_system_prompt_correction,
            R.string.system_prompt_correction_title,
            R.string.system_prompt_correction_description,
            first = true,
        )
        addSystemPromptSection(
            container,
            GenerationPromptPreferences(this),
            R.id.edit_system_prompt_generation,
            R.string.system_prompt_generation_title,
            R.string.system_prompt_generation_description,
            first = false,
        )
        addSystemPromptSection(
            container,
            TranscriptionPromptPreferences(this),
            R.id.edit_system_prompt_transcription,
            R.string.system_prompt_transcription_title,
            R.string.system_prompt_transcription_description,
            first = false,
        )

        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
    }
}
