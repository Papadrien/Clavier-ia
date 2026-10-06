package fr.junade.taipo.ai

import android.content.Context

/**
 * Prompt système de la correction ([CorrectionPrompt.SYSTEM] par défaut), modifiable depuis la page « Prompts système ».
 *
 * Contexte (décision de test du 26/09/2026) : le prompt par défaut interdit de changer les mots qui ne sont pas des
 * fautes d'orthographe ou de grammaire. Cela peut empêcher de corriger un mot mal retranscrit par la saisie vocale ;
 * la personnalisation permet d'essayer une formulation plus permissive sans recompiler. Le prompt personnalisé déjà
 * enregistré (clé `correction_system_prompt`) est conservé.
 */
class CorrectionPromptPreferences(context: Context) :
    SystemPromptPreferences(context, KEY_PROMPT, CorrectionPrompt.SYSTEM) {

    private companion object {
        const val KEY_PROMPT = "correction_system_prompt"
    }
}
