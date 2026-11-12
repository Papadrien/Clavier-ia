package fr.junade.taipo

import android.Manifest
import fr.junade.taipo.clipboard.ScreenshotFinder
import fr.junade.taipo.dictionary.SystemUserDictionary

/**
 * Autorisations Android utilisées par les fonctions de Taipo, demandées ensemble à l'étape « Autorisations » de l'accueil.
 * Chacune reste facultative : refusée, seule la fonction concernée ne marche pas (et la redemande là où elle sert).
 *
 * Logique pure (sans Context), testée en JVM : la version d'Android est passée en paramètre ([sdkInt]).
 */
enum class AppPermission {
    /** Dictée vocale et transcription IA (bouton Vocal du clavier). */
    MICROPHONE,

    /** Progression des téléchargements de modèles écran éteint (Android 13+ seulement). */
    NOTIFICATIONS,

    /** Captures d'écran récentes proposées dans le Smart Clipboard (si la fonction est compilée, voir [applicable]). */
    SCREENSHOTS,

    /** Mots du dictionnaire personnel d'Android, que l'autocorrection respecte et propose. */
    USER_DICTIONARY,
    ;

    /** Noms Android à demander pour cette autorisation (vide : sans objet sur cette version d'Android). */
    fun manifestNames(sdkInt: Int): List<String> = when (this) {
        MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
        NOTIFICATIONS -> if (sdkInt >= SDK_TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        SCREENSHOTS -> when {
            // Android 14+ : l'accès partiel est déclaré pour que le choix « quelques photos » soit reconnu (audit UX-04).
            sdkInt >= SDK_UPSIDE_DOWN_CAKE -> listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            sdkInt >= SDK_TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        USER_DICTIONARY -> listOf(SystemUserDictionary.READ_PERMISSION)
    }

    /** Nom Android qui décide si l'autorisation est accordée (le premier demandé). */
    fun grantingName(sdkInt: Int): String? = manifestNames(sdkInt).firstOrNull()

    companion object {
        private const val SDK_TIRAMISU = 33
        private const val SDK_UPSIDE_DOWN_CAKE = 34

        /**
         * Autorisations qui ont un sens sur cette version d'Android, dans l'ordre d'affichage. [declared] : autorisations que
         * l'application installée déclare dans son manifeste (null : toutes). Une fonction coupée par un flag de compilation
         * ne déclare plus son autorisation (captures d'écran du presse-papiers) : elle n'est alors pas demandée.
         * [screenshotsEnabled] : drapeau de la suggestion des captures ([ScreenshotFinder.FEATURE_ENABLED], coupée pour Google Play).
         */
        fun applicable(
            sdkInt: Int,
            declared: Set<String>? = null,
            screenshotsEnabled: Boolean = ScreenshotFinder.FEATURE_ENABLED,
        ): List<AppPermission> = entries.filter { permission ->
            val name = permission.grantingName(sdkInt) ?: return@filter false
            (permission != SCREENSHOTS || screenshotsEnabled) && (declared == null || name in declared)
        }

        /** Noms Android à demander d'un coup pour les autorisations [missing]. */
        fun toRequest(missing: Collection<AppPermission>, sdkInt: Int): Array<String> =
            missing.flatMap { it.manifestNames(sdkInt) }.distinct().toTypedArray()

        /**
         * L'étape est faite quand tout est accordé, ou quand l'utilisateur a déjà répondu à la demande ([answered]) : un
         * refus est un choix, l'étape ne reste pas en attente pour autant.
         */
        fun stepDone(granted: Set<AppPermission>, sdkInt: Int, answered: Boolean, declared: Set<String>? = null): Boolean =
            answered || granted.containsAll(applicable(sdkInt, declared))
    }
}
