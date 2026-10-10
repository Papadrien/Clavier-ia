package fr.junade.taipo

import androidx.annotation.StringRes

/**
 * Sections des conditions d'utilisation, dans l'ordre d'affichage. Les textes sont dans `strings.xml`
 * (`terms_section_<n>_title` / `terms_section_<n>_body`). La section 4 reprend les restrictions d'usage de la
 * Gemma Prohibited Use Policy, que les Gemma Terms of Use (§2.2) exigent dans les conditions de l'app.
 */
object TermsContent {
    class Section(@StringRes val title: Int, @StringRes val body: Int)

    val sections: List<Section> = listOf(
        Section(R.string.terms_section_1_title, R.string.terms_section_1_body),
        Section(R.string.terms_section_2_title, R.string.terms_section_2_body),
        Section(R.string.terms_section_3_title, R.string.terms_section_3_body),
        Section(R.string.terms_section_4_title, R.string.terms_section_4_body),
        Section(R.string.terms_section_5_title, R.string.terms_section_5_body),
        Section(R.string.terms_section_6_title, R.string.terms_section_6_body),
        Section(R.string.terms_section_7_title, R.string.terms_section_7_body),
    )
}
