package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

/**
 * Typographie de Taipo (refonte graphique, lot 02) : Open Sans, en trois graisses, pour les écrans de l'application ; la police
 * choisie dans la page « Thème » ([KeyboardFont]) pour le clavier.
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

    enum class Weight { REGULAR, MEDIUM, BOLD }

    /**
     * Police du clavier choisie dans la page « Thème » ([KeyboardFont]), déjà ramenée à Open Sans si elle n'est pas utilisable.
     * Posée au démarrage du processus et à chaque changement du réglage ou du pass premium, comme la couleur d'accent.
     */
    @Volatile
    var keyboardFont: KeyboardFont = KeyboardFont.DEFAULT

    private val cache = ConcurrentHashMap<KeyboardFont, Map<Weight, Typeface>>()

    /**
     * Police dans la graisse [weight] : Open Sans pour les écrans de l'application (contexte d'une activité), la police choisie
     * pour le clavier ([keyboardFont]) partout ailleurs (vues du service du clavier).
     */
    fun typeface(context: Context, weight: Weight = Weight.REGULAR): Typeface =
        typeface(context, if (context.isAppScreen()) KeyboardFont.DEFAULT else keyboardFont, weight)

    /** Police [font] dans la graisse [weight] (aperçus de la page « Thème »). */
    fun typeface(context: Context, font: KeyboardFont, weight: Weight): Typeface {
        val loaded = cache[font] ?: load(context, font)
        return loaded.getValue(weight)
    }

    /**
     * Police correspondant au style Android [style] (`Typeface.NORMAL`, `BOLD`, `ITALIC`, `BOLD_ITALIC`) :
     * la graisse Bold pour le gras ; l'italique est synthétisé à partir de la graisse choisie.
     */
    fun typefaceForStyle(context: Context, style: Int): Typeface {
        val bold = style and Typeface.BOLD != 0
        val italic = style and Typeface.ITALIC != 0
        val base = typeface(context, if (bold) Weight.BOLD else Weight.REGULAR)
        return if (italic) Typeface.create(base, Typeface.ITALIC) else base
    }

    private fun load(context: Context, font: KeyboardFont): Map<Weight, Typeface> {
        val resources = (context.applicationContext ?: context).resources
        return Weight.entries.associateWith { weight ->
            val res = font.fontRes(weight)
            if (res != null) resources.getFont(res) else systemTypeface(weight)
        }.also { cache[font] = it }
    }

    /** Police du téléphone : sans-serif, sans-serif-medium et sans-serif en gras. */
    private fun systemTypeface(weight: Weight): Typeface = when (weight) {
        Weight.REGULAR -> Typeface.create("sans-serif", Typeface.NORMAL)
        Weight.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        Weight.BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
    }

    /** Vrai pour un écran de l'application (le contexte mène à une activité) ; faux pour les vues du clavier. */
    private fun Context.isAppScreen(): Boolean {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return true
            current = current.baseContext
        }
        return false
    }
}

/** Pose la police de Taipo ([TaipoType.typeface]) en graisse [weight] sur ce texte (remplace `setTypeface(typeface, Typeface.BOLD)`). */
fun TextView.useTaipoFont(weight: TaipoType.Weight = TaipoType.Weight.REGULAR) {
    typeface = TaipoType.typeface(context, weight)
}

/** Pose la police de Taipo ([TaipoType.typeface]) en graisse [weight] sur ce `Paint` (texte dessiné au Canvas). */
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
