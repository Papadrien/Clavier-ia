package fr.junade.taipo.clipboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import fr.junade.taipo.AppLog
import java.io.File

/**
 * Smart Clipboard : retrouve la capture d'écran récente (MediaStore) et en prépare le collage.
 *
 * - L'accès demande la permission d'images (voir [permission]) ; sans elle, rien n'est lu.
 * - Une capture est reconnue à son nom (« Screenshot… ») ou à son dossier (« …/Screenshots/ »).
 * - Tout reste sur l'appareil : la capture n'est lue qu'au moment où l'utilisateur appuie sur la puce, copiée dans le cache de
 *   l'application (dossier [CACHE_DIR], purgé après [CACHE_MAX_AGE_MILLIS]) puis confiée à l'application cible par un
 *   FileProvider, avec une autorisation de lecture temporaire.
 */
class ScreenshotFinder(context: Context) {

    private val appContext = context.applicationContext

    fun hasPermission(): Boolean = hasPermission(appContext)

    /**
     * La capture la plus récente prise depuis [sinceMillis], ou null (aucune, accès refusé, lecture impossible).
     * À appeler hors du thread principal.
     */
    fun findLatest(sinceMillis: Long): ScreenshotSuggestionState.Shot? {
        if (!hasPermission()) return null
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.DATA
        }
        // Capture encore en cours d'écriture (IS_PENDING, Android 10+) ou fichier vide : pas encore proposée.
        val ready = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) " AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else ""
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.SIZE} > 0$ready AND " +
            "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR $pathColumn LIKE ?)"
        val args = arrayOf((sinceMillis / MILLIS_PER_SECOND).toString(), "Screenshot%", "%Screenshot%")
        return try {
            appContext.contentResolver
                .query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    ScreenshotSuggestionState.Shot(
                        id = cursor.getLong(0),
                        takenAtMillis = cursor.getLong(1) * MILLIS_PER_SECOND,
                        mimeType = cursor.getString(2)?.takeIf { it.startsWith("image/") } ?: "image/png",
                    )
                }
        } catch (e: RuntimeException) {
            // SecurityException (permission retirée entre-temps) ou fournisseur indisponible : pas de suggestion.
            AppLog.w(TAG, "Recherche de la capture d'écran impossible", e)
            null
        }
    }

    /** Capture prête à être confiée au champ : son URI (FileProvider) et son type réel, lu dans le fichier. */
    data class SharedImage(val uri: Uri, val mimeType: String)

    /**
     * Copie la capture dans le cache de l'application et renvoie l'URI (FileProvider) à confier à l'application cible, ou null
     * en cas d'échec. À appeler hors du thread principal.
     *
     * Correctif « capture corrompue » : la copie est vérifiée avant d'être confiée à l'application cible.
     * - Chaque collage a son propre fichier, écrit sous un nom temporaire puis renommé : une application qui lit encore une
     *   copie précédente ne la voit jamais réécrite sous ses yeux.
     * - La copie doit faire au moins la taille annoncée par MediaStore et se décoder comme une image.
     * - Le type annoncé est celui du fichier (en-tête décodé), pas celui de MediaStore, parfois faux (JPEG annoncé PNG…) : une
     *   application qui se fie au type annoncé croyait recevoir un fichier corrompu.
     */
    fun copyForSharing(shot: ScreenshotSuggestionState.Shot): SharedImage? {
        val dir = File(appContext.cacheDir, CACHE_DIR)
        val stamp = System.currentTimeMillis()
        val temp = File(dir, "screenshot-${shot.id}-$stamp.tmp")
        return try {
            dir.mkdirs()
            purgeOld(dir)
            val source = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, shot.id)
            val expectedBytes = sizeOf(source)
            val input = appContext.contentResolver.openInputStream(source) ?: return null
            val copiedBytes = input.use { stream ->
                temp.outputStream().use { out -> stream.copyTo(out).also { out.fd.sync() } }
            }
            if (copiedBytes <= 0L || (expectedBytes > 0L && copiedBytes < expectedBytes)) {
                AppLog.w(TAG, "Copie de la capture incomplète ($copiedBytes octets sur $expectedBytes)")
                temp.delete()
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                AppLog.w(TAG, "La copie de la capture ne se décode pas (type annoncé ${shot.mimeType}, $copiedBytes octets)")
                temp.delete()
                return null
            }
            val mimeType = bounds.outMimeType?.takeIf { it.startsWith("image/") } ?: shot.mimeType
            val target = File(dir, "screenshot-${shot.id}-$stamp.${extensionFor(mimeType)}")
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            AppLog.d(
                TAG,
                "capture copiée : $copiedBytes octets, ${bounds.outWidth}x${bounds.outHeight}, " +
                    "type MediaStore=${shot.mimeType} réel=$mimeType",
            )
            SharedImage(FileProvider.getUriForFile(appContext, authority(appContext), target), mimeType)
        } catch (e: Exception) {
            AppLog.w(TAG, "Copie de la capture d'écran impossible", e)
            temp.delete()
            null
        }
    }

    /** Taille (octets) annoncée par MediaStore pour [uri], ou -1 si inconnue. */
    private fun sizeOf(uri: Uri): Long = try {
        appContext.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    } catch (e: RuntimeException) {
        AppLog.w(TAG, "Taille de la capture inconnue", e)
        -1L
    }

    /** Supprime les copies partagées de plus d'une heure (audit UX-04 : aussi à la fermeture du clavier). Hors du fil principal. */
    fun purgeShared() {
        runCatching { purgeOld(File(appContext.cacheDir, CACHE_DIR)) }
    }

    private fun purgeOld(dir: File) {
        val limit = System.currentTimeMillis() - CACHE_MAX_AGE_MILLIS
        dir.listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    companion object {
        private const val TAG = "ScreenshotFinder"
        private const val MILLIS_PER_SECOND = 1000L

        /** Sous-dossier du cache (doit correspondre à `res/xml/file_paths.xml`). */
        const val CACHE_DIR = "shared_screenshots"

        /** Les copies du cache sont supprimées au bout d'une heure. */
        private const val CACHE_MAX_AGE_MILLIS = 60 * 60 * 1000L

        /** Permission d'images à demander selon la version d'Android. */
        val permission: String
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /** Permissions à demander : sur Android 14+, avec l'accès partiel, pour que le choix de l'utilisateur soit reconnu. */
        val requestedPermissions: Array<String>
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else {
                arrayOf(permission)
            }

        /**
         * Audit UX-04 : l'utilisateur n'a autorisé que quelques photos (Android 14+). Les captures prises ensuite ne seront pas
         * visibles : la fonction ne marche qu'avec « Tout autoriser ».
         */
        fun hasOnlyPartialAccess(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !hasPermission(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED

        fun authority(context: Context): String = context.packageName + ".fileprovider"

        /** Extension du fichier copié selon son type : le FileProvider en déduit le type qu'il annonce. */
        fun extensionFor(mimeType: String): String = when (val subtype = mimeType.substringAfter('/').lowercase()) {
            "jpeg", "pjpeg" -> "jpg"
            "" -> "png"
            else -> subtype
        }

        /** URI observé pour détecter une nouvelle capture. */
        val observedUri: Uri get() = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
}
