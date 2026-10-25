package fr.junade.taipo.ai

/**
 * Règle d'enregistrement d'un prompt système modifié dans la page « Prompts système » : un texte vide, ou identique au
 * prompt par défaut, ne crée pas de prompt personnalisé (on revient au défaut, qui suivra donc les futures mises à jour).
 *
 * Logique pure (sans Android), testée en JVM.
 */
object SystemPromptEdit {

    /** Texte à enregistrer comme prompt personnalisé, ou null s'il faut revenir au prompt par défaut. */
    fun toStore(edited: String, default: String): String? =
        if (edited.isBlank() || edited.trim() == default.trim()) null else edited
}
