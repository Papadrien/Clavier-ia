package fr.junade.taipo

/** État d'une étape de l'accueil : faite, à faire maintenant, ou pas encore accessible. */
enum class StepStatus { DONE, CURRENT, LOCKED }

/**
 * Lot UX 2 : état des étapes de l'accueil (activer le clavier, le choisir, l'essayer, activer l'IA puis la dictée), calculé à partir de
 * l'état réel d'Android. Logique pure (sans vue), testée en JVM ; la lecture du système est dans [MainActivity].
 *
 * - Étape 1 faite quand Taipo figure parmi les méthodes de saisie activées.
 * - Étape 2 inaccessible tant que Taipo n'est pas activé ; faite quand c'est la méthode de saisie par défaut.
 * - Étape 3 (essai) accessible quand Taipo est sélectionné ; faite quand quelques mots ont été tapés dans le champ d'essai ([tried]).
 * - Étape 4 (activer l'IA) accessible quand Taipo est sélectionné ; faite quand un modèle IA est installé ([aiReady]).
 * - Étape 5 (activer la dictée) accessible quand Taipo est sélectionné ; faite quand le modèle vocal est complet ([voiceReady]).
 */
data class OnboardingState(
    val enabled: Boolean,
    val selected: Boolean,
    val tried: Boolean = false,
    val aiReady: Boolean = false,
    val voiceReady: Boolean = false,
) {

    val activate: StepStatus = if (enabled) StepStatus.DONE else StepStatus.CURRENT

    val choose: StepStatus = when {
        !enabled -> StepStatus.LOCKED
        selected -> StepStatus.DONE
        else -> StepStatus.CURRENT
    }

    val tryIt: StepStatus = when {
        !(enabled && selected) -> StepStatus.LOCKED
        tried -> StepStatus.DONE
        else -> StepStatus.CURRENT
    }

    val activateAi: StepStatus = when {
        !(enabled && selected) -> StepStatus.LOCKED
        aiReady -> StepStatus.DONE
        else -> StepStatus.CURRENT
    }

    val activateVoice: StepStatus = when {
        !(enabled && selected) -> StepStatus.LOCKED
        voiceReady -> StepStatus.DONE
        else -> StepStatus.CURRENT
    }

    /** Les cinq étapes sont faites. */
    val complete: Boolean get() = enabled && selected && tried && aiReady && voiceReady

    companion object {
        /**
         * [enabledImePackages] : paquets des méthodes de saisie activées ; [defaultImeId] : identifiant de la méthode
         * par défaut (« paquet/.Service », null si inconnu) ; [packageName] : paquet de l'application.
         */
        fun from(
            enabledImePackages: Collection<String>,
            defaultImeId: String?,
            packageName: String,
            tried: Boolean = false,
            aiReady: Boolean = false,
            voiceReady: Boolean = false,
        ): OnboardingState {
            val enabled = packageName in enabledImePackages
            val selected = defaultImeId?.substringBefore('/') == packageName
            return OnboardingState(
                enabled = enabled,
                selected = enabled && selected,
                tried = tried,
                aiReady = aiReady,
                voiceReady = voiceReady,
            )
        }

        /** Nombre de mots tapés dans le champ d'essai pour que l'étape 3 soit faite. */
        const val TRY_WORDS = 3

        /** L'essai est réussi : au moins [TRY_WORDS] mots dans [text]. */
        fun triedWith(text: CharSequence): Boolean = text.split(Regex("\\s+")).count { it.isNotBlank() } >= TRY_WORDS
    }
}
