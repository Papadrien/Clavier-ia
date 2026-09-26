package fr.papadrien.clavier.ai

import android.content.Context

/**
 * Permet de personnaliser le prompt système de correction depuis les
 * paramètres, pour tester différentes formulations sans recompiler l'app.
 *
 * Contexte (décision de test du 26/09/2026) : [CorrectionPrompt.SYSTEM]
 * interdit explicitement de changer les mots qui ne sont pas des fautes
 * d'orthographe/grammaire ("Ne reformule pas... ne change pas le style").
 * C'est voulu pour la correction de texte tapé, mais ça empêche aussi de
 * corriger un mot mal retranscrit par la saisie vocale (un mot mal compris
 * par le modèle vocal est souvent un mot bien orthographié, juste faux dans
 * le contexte - donc hors du périmètre "orthographe/grammaire"). Cette classe
 * permet de tester une formulation plus permissive sans toucher au code.
 *
 * Si aucune valeur personnalisée n'est enregistrée, [get] renvoie le prompt
 * par défaut du prototype ([CorrectionPrompt.SYSTEM]).
 */
class CorrectionPromptPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Prompt système actuellement actif : personnalisé s'il y en a un, sinon celui par défaut. */
    fun get(): String = prefs.getString(KEY_PROMPT, null) ?: CorrectionPrompt.SYSTEM

    /** `true` si un prompt personnalisé est enregistré (différent du défaut du prototype). */
    fun isCustom(): Boolean = prefs.getString(KEY_PROMPT, null) != null

    fun set(prompt: String) {
        prefs.edit().putString(KEY_PROMPT, prompt).apply()
    }

    /** Revient au prompt par défaut du prototype ([CorrectionPrompt.SYSTEM]). */
    fun reset() {
        prefs.edit().remove(KEY_PROMPT).apply()
    }

    companion object {
        private const val PREFS_NAME = "ai_model_prefs"
        private const val KEY_PROMPT = "correction_system_prompt"
    }
}
