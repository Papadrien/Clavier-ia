package fr.junade.taipo.model

/**
 * Story 8.13 : quel modèle devient actif quand on supprime un modèle installé. Logique pure, testée en JVM.
 *
 * - le modèle supprimé n'était pas l'actif : l'actif ne change pas ;
 * - c'était l'actif et un autre modèle reste installé : le premier restant dans l'ordre du catalogue (Ultra-léger →
 *   Performant) devient actif ;
 * - c'était le dernier : aucun actif (null), la redirection de la story 8.5 s'applique.
 */
object ActiveModelAfterDeletion {

    fun choose(deleted: AiModel, active: AiModel?, installed: List<AiModel>): AiModel? {
        if (active != deleted) return active
        return installed.firstOrNull { it != deleted }
    }
}
