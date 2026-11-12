package fr.junade.taipo.ai

import android.content.Context
import fr.junade.taipo.CustomModelAccess
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelPreferences

/**
 * Prompt système modifiable depuis la page « Prompts système » (un par usage : correction, mode prompt). Si aucun
 * prompt personnalisé n'est enregistré, [get] renvoie le prompt par défaut [default].
 *
 * Pass premium : un prompt personnalisé n'est utilisé ([get]) que pass actif. Sans pass, il reste enregistré ([custom], exporté
 * par la sauvegarde) et revient dès que le pass est pris ; c'est le prompt par défaut qui sert.
 *
 * Modèle personnalisé ([AiModel.CUSTOM]) : il a ses propres prompts ([forCustomModel], page « Modèle personnalisé »). Quand il est le
 * modèle actif, [get] renvoie celui de l'usage s'il est enregistré (et que le modèle est autorisé, voir [CustomModelAccess]) ; sinon,
 * comme pour les autres modèles, le prompt de la page « Prompts système » ou le défaut.
 *
 * Les prompts personnalisés sont conservés dans le fichier de préférences `ai_model_prefs`, sous la clé [key] (préfixée par
 * [CUSTOM_MODEL_PREFIX] pour le modèle personnalisé).
 */
open class SystemPromptPreferences(
    context: Context,
    private val key: String,
    /** Prompt par défaut, celui du code (ne change pas tant que l'application n'est pas mise à jour). */
    val default: String,
    /** Vrai pour les prompts du modèle personnalisé ([forCustomModel]) : utilisés avec [CustomModelAccess] au lieu du pass seul. */
    private val customModelVariant: Boolean = false,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Prompt actuellement actif : celui du modèle personnalisé quand c'est le modèle actif et qu'il en a un ; sinon le prompt
     * personnalisé s'il y en a un et que le pass premium est actif ; sinon celui par défaut.
     */
    fun get(): String {
        if (!customModelVariant && ModelPreferences(appContext).activeModel() == AiModel.CUSTOM) {
            val forModel = forCustomModel()
            forModel.custom()?.takeIf { forModel.allowed() }?.let { return it }
        }
        return activePrompt(custom(), default, allowed())
    }

    private fun allowed(): Boolean = if (customModelVariant) CustomModelAccess.allowed else fr.junade.taipo.PremiumPass.isActive

    /** Prompts du même usage pour le modèle personnalisé (même défaut, clé à part). */
    fun forCustomModel(): SystemPromptPreferences =
        if (customModelVariant) this else SystemPromptPreferences(appContext, CUSTOM_MODEL_PREFIX + key, default, customModelVariant = true)

    /** Prompt personnalisé enregistré, utilisé ou non (null : aucun). */
    fun custom(): String? = prefs.getString(key, null)

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

    companion object {
        private const val PREFS_NAME = "ai_model_prefs"

        /** Préfixe des clés des prompts du modèle personnalisé (« custom_model_correction_system_prompt »...). */
        const val CUSTOM_MODEL_PREFIX = "custom_model_"

        /** Logique pure (testée en JVM) : le prompt personnalisé ne sert qu'avec le pass. */
        fun activePrompt(custom: String?, default: String, premium: Boolean): String = if (premium && custom != null) custom else default
    }
}
