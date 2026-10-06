package fr.junade.taipo

import android.content.Context
import android.graphics.drawable.Icon
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Style envoyé au service d'auto-remplissage (Bitwarden…) pour que ses suggestions en ligne suivent le thème du clavier
 * (voir [InlineSuggestionColors]) : fond de la pastille, couleur du titre et du sous-titre. Les icônes gardent leurs
 * couleurs d'origine (logo du gestionnaire de mots de passe).
 */
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
