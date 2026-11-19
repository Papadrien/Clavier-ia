package fr.junade.taipo.backdrop

import kotlin.math.max
import kotlin.math.roundToInt

/** Rectangle en pixels entiers (bornes droite et bas exclues), sans dépendance à Android : testable en JVM. */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Géométrie du fond image (page « Apparence », fond « Image ») : réduction à l'import et recadrage au centre pour remplir le
 * clavier. Logique pure, testée en JVM (`PhotoGeometryTest`).
 */
object PhotoGeometry {

    /**
     * Facteur de sous-échantillonnage du décodage (puissance de 2) : le plus grand qui garde le grand côté de l'image
     * ([width] × [height]) au moins égal à [maxSide]. 1 pour une image déjà assez petite ou des dimensions invalides.
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        val longSide = max(width, height)
        if (longSide <= 0 || maxSide <= 0) return 1
        var sample = 1
        while (longSide / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** Dimensions de l'image ramenée à [maxSide] au plus sur son grand côté (proportions gardées, jamais agrandie). */
    fun fitInside(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longSide = max(width, height)
        if (longSide <= maxSide || longSide <= 0) return width to height
        val scale = maxSide.toFloat() / longSide
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Partie de l'image ([srcWidth] × [srcHeight]) à afficher dans une zone [dstWidth] × [dstHeight] pour la remplir sans
     * déformation : la plus grande zone aux proportions de la destination, centrée (recadrage « center crop »).
     */
    fun centerCrop(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): CropRect {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return CropRect(0, 0, srcWidth.coerceAtLeast(0), srcHeight.coerceAtLeast(0))
        }
        // Comparaison en Long : srcWidth × dstHeight peut dépasser Int pour de grandes images.
        return if (srcWidth.toLong() * dstHeight > dstWidth.toLong() * srcHeight) {
            // Image plus large que la zone : on garde toute la hauteur, on coupe à gauche et à droite.
            val width = (srcHeight.toLong() * dstWidth / dstHeight).toInt().coerceIn(1, srcWidth)
            val left = (srcWidth - width) / 2
            CropRect(left, 0, left + width, srcHeight)
        } else {
            // Image plus haute que la zone : on garde toute la largeur, on coupe en haut et en bas.
            val height = (srcWidth.toLong() * dstHeight / dstWidth).toInt().coerceIn(1, srcHeight)
            val top = (srcHeight - height) / 2
            CropRect(0, top, srcWidth, top + height)
        }
    }
}
