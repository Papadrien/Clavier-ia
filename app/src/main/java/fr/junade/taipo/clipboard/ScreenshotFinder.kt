package fr.junade.taipo.clipboard

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
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
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND " +
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

    /**
     * Copie la capture dans le cache de l'application et renvoie l'URI (FileProvider) à confier à l'application cible, ou null
     * en cas d'échec. À appeler hors du thread principal.
     */
    fun copyForSharing(shot: ScreenshotSuggestionState.Shot): Uri? {
        return try {
            val dir = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }
            purgeOld(dir)
            val extension = if (shot.mimeType == "image/jpeg") "jpg" else shot.mimeType.substringAfter('/').ifBlank { "png" }
            val target = File(dir, "screenshot-${shot.id}.$extension")
            val source = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, shot.id)
            val input = appContext.contentResolver.openInputStream(source) ?: return null
            input.use { stream -> target.outputStream().use { out -> stream.copyTo(out) } }
            FileProvider.getUriForFile(appContext, authority(appContext), target)
        } catch (e: Exception) {
            AppLog.w(TAG, "Copie de la capture d'écran impossible", e)
            null
        }
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

        fun authority(context: Context): String = context.packageName + ".fileprovider"

        /** URI observé pour détecter une nouvelle capture. */
        val observedUri: Uri get() = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
}
