package fr.junade.taipo

/** État d'une étape de l'accueil : faite, à faire maintenant, ou pas encore accessible. */
enum class StepStatus { DONE, CURRENT, LOCKED }

/**
 * Lot UX 2 : état des trois étapes de l'accueil (activer le clavier, le choisir, l'essayer), calculé à partir de
 * l'état réel d'Android. Logique pure (sans vue), testée en JVM ; la lecture du système est dans [MainActivity].
 *
 * - Étape 1 faite quand Taipo figure parmi les méthodes de saisie activées.
 * - Étape 2 inaccessible tant que Taipo n'est pas activé ; faite quand c'est la méthode de saisie par défaut.
 * - Étape 3 (essai) accessible quand Taipo est sélectionné ; elle n'est jamais « faite » (rien ne permet de le savoir).
 */
data class OnboardingState(val enabled: Boolean, val selected: Boolean) {

    val activate: StepStatus = if (enabled) StepStatus.DONE else StepStatus.CURRENT

    val choose: StepStatus = when {
        !enabled -> StepStatus.LOCKED
        selected -> StepStatus.DONE
        else -> StepStatus.CURRENT
    }

    val tryIt: StepStatus = if (enabled && selected) StepStatus.CURRENT else StepStatus.LOCKED

    companion object {
        /**
         * [enabledImePackages] : paquets des méthodes de saisie activées ; [defaultImeId] : identifiant de la méthode
         * par défaut (« paquet/.Service », null si inconnu) ; [packageName] : paquet de l'application.
         */
        fun from(enabledImePackages: Collection<String>, defaultImeId: String?, packageName: String): OnboardingState {
            val enabled = packageName in enabledImePackages
            val selected = defaultImeId?.substringBefore('/') == packageName
            return OnboardingState(enabled = enabled, selected = enabled && selected)
        }
    }
}
