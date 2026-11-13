package fr.junade.taipo

import android.app.Activity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import fr.junade.taipo.ai.SystemPromptPreferences

/**
 * Ajoute à [container] une section d'édition d'un prompt système (titre, description, champ, Enregistrer / Voir le défaut /
 * Réinitialiser, état). Pages « Prompts système » et « Modèle personnalisé ». [editId] : identifiant propre au champ, pour que son
 * contenu survive à la rotation. [first] : pas de séparateur au-dessus.
 */
internal fun Activity.addSystemPromptSection(
    container: LinearLayout,
    preferences: SystemPromptPreferences,
    @IdRes editId: Int,
    @StringRes title: Int,
    @StringRes description: Int,
    first: Boolean,
) {
    if (!first) {
        layoutInflater.inflate(R.layout.view_settings_divider, container, true)
    }
    val section = LayoutInflater.from(this).inflate(R.layout.view_system_prompt_section, container, false)
    container.addView(section)
    // Identifiant propre à chaque section : sans lui, les champs partageraient le même état à la rotation.
    val edit = section.findViewById<EditText>(R.id.edit_system_prompt).also { it.id = editId }
    val status = section.findViewById<TextView>(R.id.text_system_prompt_status)
    section.findViewById<TextView>(R.id.text_system_prompt_title).apply {
        setText(title)
        labelFor = editId // audit A11Y-03 : TalkBack nomme le champ par le titre de sa section
    }
    section.findViewById<TextView>(R.id.text_system_prompt_description).setText(description)

    fun refreshStatus() {
        status.setText(
            if (preferences.isCustom()) R.string.system_prompt_status_custom else R.string.system_prompt_status_default,
        )
    }
    val save = section.findViewById<Button>(R.id.button_save_system_prompt)
    edit.setText(preferences.custom() ?: preferences.default)
    refreshStatus()

    save.setOnClickListener {
        preferences.save(edit.text.toString())
        edit.setText(preferences.custom() ?: preferences.default) // champ vide ou identique au défaut : on revoit le défaut
        refreshStatus()
        showScreenMessage(R.string.system_prompt_saved)
    }
    // Consulter le défaut sans perdre le prompt personnalisé : il est seulement affiché, pas enregistré.
    section.findViewById<Button>(R.id.button_show_default_system_prompt).setOnClickListener {
        edit.setText(preferences.default)
        showScreenMessage(R.string.system_prompt_default_shown)
    }
    section.findViewById<Button>(R.id.button_reset_system_prompt).setOnClickListener {
        preferences.reset()
        edit.setText(preferences.default)
        refreshStatus()
        showScreenMessage(R.string.system_prompt_reset_done)
    }
}
