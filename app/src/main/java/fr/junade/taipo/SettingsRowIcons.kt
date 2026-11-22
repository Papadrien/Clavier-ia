package fr.junade.taipo

import android.app.Activity
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes

/**
 * Icônes des lignes de réglage des écrans (22/11/2026) : une icône au trait, couleur d'accent, sur une pastille à gauche du titre
 * ([R.layout.view_settings_row], vue `row_icon`). Décoratives : le titre de la ligne porte le sens pour TalkBack.
 */
object SettingsRowIcons {

    /** Ligne (id du `<include>`) → icône. */
    val ICONS: Map<Int, Int> = mapOf(
        R.id.button_home_languages to R.drawable.ic_row_languages,
        R.id.button_languages to R.drawable.ic_row_languages,
        R.id.button_keyboard_size to R.drawable.ic_row_keyboard_size,
        R.id.button_keyboard_layout to R.drawable.ic_row_keyboard_layout,
        R.id.button_keyboard_sounds to R.drawable.ic_row_keyboard_sounds,
        R.id.button_keyboard_smart to R.drawable.ic_row_keyboard_smart,
        R.id.button_personal_dictionary to R.drawable.ic_row_dictionary,
        R.id.button_appearance_theme to R.drawable.ic_row_theme,
        R.id.button_appearance_keys to R.drawable.ic_row_keys,
        R.id.button_appearance_backdrop to R.drawable.ic_row_backdrop,
        R.id.button_appearance_motion to R.drawable.ic_row_motion,
        R.id.button_model_download to R.drawable.ic_row_model,
        R.id.button_model_settings to R.drawable.ic_row_model_settings,
        R.id.button_voice_model_settings to R.drawable.ic_row_voice,
        R.id.button_voice_model_local to R.drawable.ic_row_folder,
        R.id.button_system_prompts to R.drawable.ic_row_prompts,
        R.id.button_custom_model to R.drawable.ic_row_custom_model,
        R.id.button_backup to R.drawable.ic_row_backup,
        R.id.button_premium to R.drawable.ic_row_premium,
        R.id.button_whats_new to R.drawable.ic_row_whats_new,
        R.id.button_terms to R.drawable.ic_row_terms,
        R.id.button_licenses to R.drawable.ic_row_licenses,
        R.id.button_privacy to R.drawable.ic_row_privacy,
    )

    /** Pose l'icône de chaque ligne de [activity] qui en a une. */
    fun apply(activity: Activity) {
        for ((row, icon) in ICONS) activity.findViewById<View>(row)?.let { set(it, icon) }
    }

    fun set(row: View, @DrawableRes icon: Int) {
        row.findViewById<ImageView>(R.id.row_icon)?.apply {
            setImageResource(icon)
            visibility = View.VISIBLE
        }
    }
}
