package fr.junade.taipo.backdrop

import androidx.annotation.StringRes
import fr.junade.taipo.R

/**
 * Fond du clavier (page « Thème », section « Fond du clavier ») : uni, ou un motif généré dans la couleur d'accent et le thème
 * clair ou sombre (maquettes validées le 10/11/2026), ou une image choisie par l'utilisateur ([PHOTO], 19/11/2026). Uni reste
 * gratuit ; les motifs et l'image sont réservés au pass premium.
 */
enum class KeyboardBackdrop(val storageKey: String, @StringRes val labelRes: Int) {
    PLAIN("plain", R.string.backdrop_plain),
    RIBBONS("ribbons", R.string.backdrop_ribbons),
    BUBBLES("bubbles", R.string.backdrop_bubbles),
    CRYSTAL("crystal", R.string.backdrop_crystal),
    WAVES("waves", R.string.backdrop_waves),

    // 13/11/2026 : cœurs en néon et pâquerettes (maquettes validées le jour même).
    HEARTS("hearts", R.string.backdrop_hearts),
    FLOWERS("flowers", R.string.backdrop_flowers),

    // 16/11/2026 : circuit imprimé et processeur (maquette « Processeur » retenue le jour même).
    PROCESSOR("processor", R.string.backdrop_processor),

    // 19/11/2026 : image choisie dans la galerie ou le sélecteur de fichiers (voir PhotoBackdrop).
    PHOTO("photo", R.string.backdrop_photo),
    ;

    /** Un motif ou une image (et non le fond uni) : touches translucides par-dessus, voile sous la barre du haut. */
    val isPattern: Boolean get() = this != PLAIN

    /** Un motif dessiné par l'application : animé pendant la frappe, disposition à retirer. Pas l'image de l'utilisateur. */
    val isGenerated: Boolean get() = isPattern && this != PHOTO

    val requiresPremium: Boolean get() = isPattern

    fun isAvailable(premium: Boolean): Boolean = premium || !requiresPremium

    companion object {
        val DEFAULT = PLAIN

        fun fromStorageKey(key: String?): KeyboardBackdrop = entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        /** Fond à appliquer : le motif choisi s'il est utilisable, sinon le fond uni (pass premium arrêté, par exemple). */
        fun effective(stored: KeyboardBackdrop, premium: Boolean): KeyboardBackdrop =
            if (stored.isAvailable(premium)) stored else DEFAULT

        /** Comme [effective], et le fond uni quand l'image n'a pas (ou plus) de copie enregistrée ([hasPhoto] faux). */
        fun effective(stored: KeyboardBackdrop, premium: Boolean, hasPhoto: Boolean): KeyboardBackdrop {
            val shown = effective(stored, premium)
            return if (shown == PHOTO && !hasPhoto) DEFAULT else shown
        }
    }
}
