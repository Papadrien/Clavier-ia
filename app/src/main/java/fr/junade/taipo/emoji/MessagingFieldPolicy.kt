package fr.junade.taipo.emoji

import android.text.InputType
import fr.junade.taipo.suggestion.SuggestionPolicy

/**
 * Décide si le champ courant est celui d'une application de messagerie, où la barre d'emojis
 * récents est proposée au-dessus de la barre du haut. Un champ est « de messagerie » quand
 * l'application le déclare comme tel (variation message court ou long) ou quand elle figure dans
 * la liste des messageries courantes. Les champs sans texte libre (mot de passe, e-mail, adresse
 * web, filtre, nombre...) sont toujours exclus. Logique pure : elle ne lit que des constantes.
 */
object MessagingFieldPolicy {

    private val MESSAGING_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android",
        "com.snapchat.android",
        "com.discord",
        "com.slack",
        "com.microsoft.teams",
        "com.google.android.apps.messaging",
        "com.google.android.apps.dynamite",
        "com.samsung.android.messaging",
        "com.android.mms",
        "jp.naver.line.android",
        "com.viber.voip",
        "com.tencent.mm",
        "com.skype.raider",
        "im.vector.app",
    )

    fun isMessagingField(packageName: String?, inputType: Int): Boolean {
        if (!SuggestionPolicy.allowsSuggestions(inputType)) return false
        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            -> true

            else -> packageName in MESSAGING_PACKAGES
        }
    }
}
