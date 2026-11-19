package fr.junade.taipo.backdrop

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import androidx.annotation.WorkerThread
import fr.junade.taipo.AppLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Fond « Image » (page « Apparence ») : une image choisie par l'utilisateur (galerie ou sélecteur de fichiers) remplit le fond
 * du clavier.
 *
 * L'image n'est pas lue à chaque ouverture depuis sa source (l'accès au fichier d'origine peut être retiré) : [store] en garde
 * une copie privée, redressée selon son orientation EXIF et ramenée à [MAX_SIDE_PX] sur son grand côté, en JPEG, dans
 * `files/backdrop/photo.jpg`. Le dossier est exclu des sauvegardes (`backup_rules.xml`, `data_extraction_rules.xml`) : une photo
 * personnelle ne part pas dans le cloud ; après restauration, le fond revient à Uni ([KeyboardBackdrop.effective]).
 */
object PhotoBackdrop {

    /** Grand côté maximal de la copie gardée : assez pour un clavier en paysage, environ 6 Mo une fois décodée. */
    const val MAX_SIDE_PX = 1440

    private const val DIR = "backdrop"
    private const val FILE = "photo.jpg"
    private const val JPEG_QUALITY = 90
    private const val TAG = "PhotoBackdrop"

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)

    fun exists(context: Context): Boolean = file(context).isFile

    /**
     * Importe l'image [uri] : décodée, redressée, réduite, puis enregistrée (fichier temporaire puis renommage, l'image
     * précédente reste en place si l'import échoue). Renvoie false si l'image ne peut pas être lue. Hors du thread principal.
     */
    @WorkerThread
    fun store(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        return try {
            val bitmap = decodeUri(resolver, uri) ?: return false
            val target = file(context)
            val dir = target.parentFile ?: return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val tmp = File(dir, "$FILE.tmp")
            val written = FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            bitmap.recycle()
            if (!written || !tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            true
        } catch (e: IOException) {
            AppLog.w(TAG, "import de l'image de fond", e)
            false
        } catch (e: SecurityException) {
            AppLog.w(TAG, "accès refusé à l'image de fond", e)
            false
        } catch (e: IllegalArgumentException) {
            AppLog.w(TAG, "image de fond invalide", e)
            false
        } catch (e: OutOfMemoryError) {
            AppLog.w(TAG, "image de fond trop grande", e)
            false
        }
    }

    /** Copie privée décodée, réduite à [maxSide] au besoin (vignette de la page « Apparence »), ou null si absente ou illisible. */
    @WorkerThread
    fun load(context: Context, maxSide: Int = MAX_SIDE_PX): Bitmap? {
        val source = file(context)
        if (!source.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = PhotoGeometry.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
            BitmapFactory.decodeFile(source.path, options)
        } catch (e: OutOfMemoryError) {
            AppLog.w(TAG, "lecture de l'image de fond", e)
            null
        }
    }

    /** Décode [uri] en deux passes (dimensions, puis image sous-échantillonnée), réduit à [MAX_SIDE_PX] et redresse (EXIF). */
    private fun decodeUri(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Lecture des seules dimensions : decodeStream rend null, on regarde bounds.
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = PhotoGeometry.sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE_PX) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val (width, height) = PhotoGeometry.fitInside(decoded.width, decoded.height, MAX_SIDE_PX)
        if (rotation == 0 && width == decoded.width && height == decoded.height) return decoded
        val matrix = Matrix().apply {
            postScale(width.toFloat() / decoded.width, height.toFloat() / decoded.height)
            postRotate(rotation.toFloat())
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    /** Rotation (degrés) indiquée par l'EXIF d'une photo ; 0 sans EXIF ou pour un format qui n'en a pas (PNG, WebP…). */
    private fun exifRotation(stream: java.io.InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    } catch (e: IOException) {
        AppLog.w(TAG, "orientation de l'image de fond", e)
        0
    }
}

/**
 * Dessin du fond « Image », commun au clavier ([fr.junade.taipo.KeyboardBackgroundDrawable]) et à la vignette de la page
 * « Apparence » ([BackdropPreviewView]) : l'image remplit la zone, recadrée au centre ([PhotoGeometry.centerCrop]), sous un léger
 * voile de la couleur du clavier ([DIM_ALPHA]) pour que les touches translucides et leurs lettres restent lisibles sur une photo
 * très claire ou très chargée.
 */
class PhotoBackdropDrawer {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = Rect()

    fun draw(canvas: Canvas, photo: Bitmap, width: Int, height: Int, base: Int) {
        canvas.drawColor(base)
        if (width <= 0 || height <= 0 || photo.isRecycled) return
        val crop = PhotoGeometry.centerCrop(photo.width, photo.height, width, height)
        src.set(crop.left, crop.top, crop.right, crop.bottom)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(photo, src, dst, paint)
        canvas.drawColor(BackdropColors.withAlpha(base, DIM_ALPHA))
    }

    private companion object {
        /** Opacité du voile posé sur l'image : la photo reste nette, les lettres des touches gardent du contraste. */
        const val DIM_ALPHA = 0.2f
    }
}
