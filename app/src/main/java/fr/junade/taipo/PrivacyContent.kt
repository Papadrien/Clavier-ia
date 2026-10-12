package fr.junade.taipo

import androidx.annotation.StringRes

/**
 * Sections du menu « Confidentialité » (story 10.2), dans l'ordre d'affichage. Texte seul : aucune bascule, car l'application
 * ne contient ni télémétrie ni mesure d'audience à désactiver. Les textes sont dans `strings.xml`
 * (`privacy_section_<n>_title` / `privacy_section_<n>_body`).
 *
 * À tenir à jour avec le code : toute nouvelle connexion réseau (nouveau domaine de téléchargement, outil de mesure d'audience
 * comme Aptabase, etc.) doit être ajoutée à la section 2 ou 3 ; `PrivacyContentTest` échoue si un modèle est téléchargé
 * depuis un domaine que le texte ne mentionne pas.
 */
object PrivacyContent {
    class Section(@StringRes val title: Int, @StringRes val body: Int)

    val sections: List<Section> = listOf(
        Section(R.string.privacy_section_1_title, R.string.privacy_section_1_body),
        Section(R.string.privacy_section_2_title, R.string.privacy_section_2_body),
        Section(R.string.privacy_section_3_title, R.string.privacy_section_3_body),
        Section(R.string.privacy_section_4_title, R.string.privacy_section_4_body),
        Section(R.string.privacy_section_5_title, R.string.privacy_section_5_body),
    )
}
