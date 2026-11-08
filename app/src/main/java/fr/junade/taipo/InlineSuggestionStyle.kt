package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Style envoyé au service d'auto-remplissage (Bitwarden…) pour que ses suggestions en ligne suivent le thème du clavier
 * (voir [InlineSuggestionColors]) : fond de la pastille, couleur du titre et du sous-titre. Les icônes gardent leurs
 * couleurs d'origine (logo du gestionnaire de mots de passe).
 */
// Android 11+ seulement (appelé par InlineSuggestionsController.createRequest, lui-même réservé à l'API 30).
// `ViewStyle.Builder.setBackground` est marqué « restreint » dans androidx.autofill alors que c'est l'API documentée pour
// le fond des pastilles : faux positif connu du lint, supprimé ici seulement.
@RequiresApi(Build.VERSION_CODES.R)
@SuppressLint("RestrictedApi")
internal fun buildInlineSuggestionStyle(context: Context): UiVersions.Style {
    val colors = InlineSuggestionColors.forMode(
        KeyboardTheme.mode,
        darkTitle = context.themeColor(R.color.text_primary),
        darkSubtitle = context.themeColor(R.color.text_hint),
    )
    val chip = ViewStyle.Builder()
        .setBackground(Icon.createWithResource(context, colors.chipBackground))
        .build()
    val title = TextViewStyle.Builder().setTextColor(colors.title).build()
    val subtitle = TextViewStyle.Builder().setTextColor(colors.subtitle).build()
    return InlineSuggestionUi.newStyleBuilder()
        .setChipStyle(chip)
        .setSingleIconChipStyle(chip)
        .setTitleStyle(title)
        .setSubtitleStyle(subtitle)
        .build()
}
