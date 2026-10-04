package fr.junade.taipo

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.FontRes

/**
 * Typographie de Taipo (refonte graphique, lot 02) : Open Sans, en trois graisses.
 *
 * Les polices sont chargées une seule fois (chargement paresseux, mis en cache) : aucun accès aux ressources
 * dans `onDraw` ni à chaque création de vue. Ce lot ne change aucune taille de texte.
 *
 * Utilisation :
 * - `TextView` / `Button` : [useTaipoFont] (graisse explicite) ou [applyTaipoFontToTree] (conserve le gras et
 *   l'italique déjà demandés, par `textStyle` par exemple) ;
 * - `Paint` du Canvas : [useTaipoFont].
 *
 * Les vues d'un clavier (IME) ne reçoivent pas le thème de l'application : la police doit donc être posée
 * explicitement, d'où ces fonctions plutôt qu'un simple `android:fontFamily` dans le thème.
 */
object TaipoType {

    enum class Weight(@FontRes val fontRes: Int) {
        REGULAR(R.font.opensans_regular),
        MEDIUM(R.font.opensans_medium),
        BOLD(R.font.opensans_bold),
    }

    @Volatile
    private var cache: Map<Weight, Typeface>? = null

    /** Open Sans dans la graisse [weight]. */
    fun typeface(context: Context, weight: Weight = Weight.REGULAR): Typeface {
        val loaded = cache ?: load(context)
        return loaded.getValue(weight)
    }

    /**
     * Police correspondant au style Android [style] (`Typeface.NORMAL`, `BOLD`, `ITALIC`, `BOLD_ITALIC`) :
     * Open Sans Bold pour le gras ; l'italique est synthétisé à partir de la graisse choisie.
     */
    fun typefaceForStyle(context: Context, style: Int): Typeface {
        val bold = style and Typeface.BOLD != 0
        val italic = style and Typeface.ITALIC != 0
        val base = typeface(context, if (bold) Weight.BOLD else Weight.REGULAR)
        return if (italic) Typeface.create(base, Typeface.ITALIC) else base
    }

    private fun load(context: Context): Map<Weight, Typeface> {
        val resources = (context.applicationContext ?: context).resources
        return Weight.values().associateWith { resources.getFont(it.fontRes) }.also { cache = it }
    }
}

/** Pose Open Sans [weight] sur ce texte (remplace `setTypeface(typeface, Typeface.BOLD)`). */
fun TextView.useTaipoFont(weight: TaipoType.Weight = TaipoType.Weight.REGULAR) {
    typeface = TaipoType.typeface(context, weight)
}

/** Pose Open Sans [weight] sur ce `Paint` (texte dessiné au Canvas). */
fun Paint.useTaipoFont(context: Context, weight: TaipoType.Weight = TaipoType.Weight.REGULAR) {
    typeface = TaipoType.typeface(context, weight)
}

/**
 * Pose Open Sans sur tous les textes de cette vue et de ses enfants, en conservant le gras et l'italique déjà
 * demandés (`android:textStyle`, `setTypeface(…, BOLD)`). À appeler une fois les vues construites ; les vues
 * ajoutées ensuite doivent être traitées à leur création.
 */
fun View.applyTaipoFontToTree() {
    if (this is TextView) {
        typeface = TaipoType.typefaceForStyle(context, typeface?.style ?: Typeface.NORMAL)
    }
    if (this is ViewGroup) {
        for (index in 0 until childCount) getChildAt(index).applyTaipoFontToTree()
    }
}
