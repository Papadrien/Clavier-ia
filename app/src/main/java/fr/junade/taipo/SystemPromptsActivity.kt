package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import fr.junade.taipo.ai.CorrectionPromptPreferences
import fr.junade.taipo.ai.GenerationPromptPreferences
import fr.junade.taipo.ai.SystemPromptPreferences

/**
 * Page « Prompts système » : consulter, modifier et réinitialiser les prompts système envoyés au modèle IA, un par
 * usage (correction, mode prompt). Chaque champ affiche le prompt actuellement utilisé (le défaut tant qu'aucun prompt
 * personnalisé n'est enregistré).
 */
class SystemPromptsActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_prompts)
        val container = findViewById<LinearLayout>(R.id.container_system_prompts)

        addSection(
            container,
            CorrectionPromptPreferences(this),
            R.id.edit_system_prompt_correction,
            R.string.system_prompt_correction_title,
            R.string.system_prompt_correction_description,
            first = true,
        )
        addSection(
            container,
            GenerationPromptPreferences(this),
            R.id.edit_system_prompt_generation,
            R.string.system_prompt_generation_title,
            R.string.system_prompt_generation_description,
            first = false,
        )

        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
    }

    private fun addSection(
        container: LinearLayout,
        preferences: SystemPromptPreferences,
        editId: Int,
        @StringRes title: Int,
        @StringRes description: Int,
        first: Boolean,
    ) {
        if (!first) {
            layoutInflater.inflate(R.layout.view_settings_divider, container, true)
        }
        val section = LayoutInflater.from(this).inflate(R.layout.view_system_prompt_section, container, false)
        container.addView(section)
        // Identifiant propre à chaque section : sans lui, les deux champs partageraient le même état à la rotation.
        val edit = section.findViewById<EditText>(R.id.edit_system_prompt).also { it.id = editId }
        val status = section.findViewById<TextView>(R.id.text_system_prompt_status)
        section.findViewById<TextView>(R.id.text_system_prompt_title).setText(title)
        section.findViewById<TextView>(R.id.text_system_prompt_description).setText(description)

        fun refreshStatus() {
            status.setText(
                if (preferences.isCustom()) R.string.system_prompt_status_custom else R.string.system_prompt_status_default,
            )
        }
        edit.setText(preferences.get())
        refreshStatus()

        section.findViewById<Button>(R.id.button_save_system_prompt).setOnClickListener {
            preferences.save(edit.text.toString())
            edit.setText(preferences.get()) // champ vide ou identique au défaut : on revoit le défaut
            refreshStatus()
            Toast.makeText(this, R.string.system_prompt_saved, Toast.LENGTH_SHORT).show()
        }
        // Consulter le défaut sans perdre le prompt personnalisé : il est seulement affiché, pas enregistré.
        section.findViewById<Button>(R.id.button_show_default_system_prompt).setOnClickListener {
            edit.setText(preferences.default)
            Toast.makeText(this, R.string.system_prompt_default_shown, Toast.LENGTH_SHORT).show()
        }
        section.findViewById<Button>(R.id.button_reset_system_prompt).setOnClickListener {
            preferences.reset()
            edit.setText(preferences.get())
            refreshStatus()
            Toast.makeText(this, R.string.system_prompt_reset_done, Toast.LENGTH_SHORT).show()
        }
    }
}
