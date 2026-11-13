package fr.junade.taipo

import androidx.annotation.FontRes
import androidx.annotation.StringRes

/**
 * Police du clavier (page « Thème », section « Police ») : touches, suggestions, barre du haut, panneaux. Les écrans de
 * l'application gardent toujours Open Sans.
 *
 * Open Sans reste la police par défaut, gratuite ; les autres sont réservées au pass premium ([PremiumPass]). Toutes sont sous
 * licence SIL OFL 1.1, livrées sans modification (fichiers de Google Fonts, et du projet OpenDyslexic) et citées dans l'écran
 * « Licences ». Trois graisses par police ; OpenDyslexic n'a pas de Medium : sa graisse normale en tient lieu. [SYSTEM] prend
 * la police du téléphone (aucun fichier embarqué).
 */
enum class KeyboardFont(
    val storageKey: String,
    /** Nom affiché : le nom propre de la police (non traduit), sauf pour la police du système. */
    val displayName: String?,
    @StringRes val labelRes: Int?,
    @FontRes val regular: Int?,
    @FontRes val medium: Int?,
    @FontRes val bold: Int?,
) {
    OPEN_SANS("open_sans", "Open Sans", null, R.font.opensans_regular, R.font.opensans_medium, R.font.opensans_bold),
    SYSTEM("system", null, R.string.keyboard_font_system, null, null, null),
    INTER("inter", "Inter", null, R.font.inter_regular, R.font.inter_medium, R.font.inter_bold),
    ATKINSON(
        "atkinson",
        "Atkinson Hyperlegible",
        null,
        R.font.atkinson_regular,
        R.font.atkinson_medium,
        R.font.atkinson_bold,
    ),
    LEXEND("lexend", "Lexend", null, R.font.lexend_regular, R.font.lexend_medium, R.font.lexend_bold),
    NUNITO("nunito", "Nunito", null, R.font.nunito_regular, R.font.nunito_medium, R.font.nunito_bold),
    MANROPE("manrope", "Manrope", null, R.font.manrope_regular, R.font.manrope_medium, R.font.manrope_bold),
    IBM_PLEX_SANS(
        "ibm_plex_sans",
        "IBM Plex Sans",
        null,
        R.font.ibmplexsans_regular,
        R.font.ibmplexsans_medium,
        R.font.ibmplexsans_bold,
    ),
    LITERATA("literata", "Literata", null, R.font.literata_regular, R.font.literata_medium, R.font.literata_bold),
    OPEN_DYSLEXIC(
        "opendyslexic",
        "OpenDyslexic",
        null,
        R.font.opendyslexic_regular,
        R.font.opendyslexic_regular,
        R.font.opendyslexic_bold,
    ),
    ;

    /** Réservée au pass premium ? Seule Open Sans, la police de Taipo, reste toujours proposée. */
    val requiresPremium: Boolean get() = this != DEFAULT

    /** Utilisable maintenant, sachant si le pass premium est actif ([premium]). */
    fun isAvailable(premium: Boolean): Boolean = premium || !requiresPremium

    /** Fichier de la graisse [weight], ou null pour la police du système. */
    @FontRes
    fun fontRes(weight: TaipoType.Weight): Int? = when (weight) {
        TaipoType.Weight.REGULAR -> regular
        TaipoType.Weight.MEDIUM -> medium
        TaipoType.Weight.BOLD -> bold
    }

    companion object {
        val DEFAULT = OPEN_SANS

        fun fromStorageKey(key: String?): KeyboardFont = entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /** Police à appliquer : celle choisie ([stored]) si elle est utilisable, sinon Open Sans (pass premium arrêté, par exemple). */
        fun effective(stored: KeyboardFont, premium: Boolean): KeyboardFont = if (stored.isAvailable(premium)) stored else DEFAULT
    }
}
