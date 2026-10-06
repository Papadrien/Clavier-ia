package fr.junade.taipo.ai

import android.content.Context

/**
 * Prompt système modifiable depuis la page « Prompts système » (un par usage : correction, mode prompt). Si aucun
 * prompt personnalisé n'est enregistré, [get] renvoie le prompt par défaut [default].
 *
 * Les prompts personnalisés sont conservés dans le fichier de préférences `ai_model_prefs`, sous la clé [key].
 */
open class SystemPromptPreferences(
    context: Context,
    private val key: String,
    /** Prompt par défaut, celui du code (ne change pas tant que l'application n'est pas mise à jour). */
    val default: String,
) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Prompt actuellement actif : personnalisé s'il y en a un, sinon celui par défaut. */
    fun get(): String = prefs.getString(key, null) ?: default

    /** `true` si un prompt personnalisé est enregistré. */
    fun isCustom(): Boolean = prefs.getString(key, null) != null

    fun set(prompt: String) {
        prefs.edit().putString(key, prompt).apply()
    }

    /** Enregistre [edited] comme prompt personnalisé, ou revient au défaut s'il est vide ou identique (voir [SystemPromptEdit]). */
    fun save(edited: String) {
        val toStore = SystemPromptEdit.toStore(edited, default)
        if (toStore == null) reset() else set(toStore)
    }

    /** Revient au prompt par défaut. */
    fun reset() {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val PREFS_NAME = "ai_model_prefs"
    }
}
