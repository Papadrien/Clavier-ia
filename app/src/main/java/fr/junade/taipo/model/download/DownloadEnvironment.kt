package fr.junade.taipo.model.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs

/** Lectures de l'état de l'appareil utiles au téléchargement : réseau actif et espace libre. */
object DownloadEnvironment {

    fun networkKind(context: Context): NetworkKind {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.NONE
        val network = manager.activeNetwork ?: return NetworkKind.NONE
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkKind.NONE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.NONE
        return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkKind.UNMETERED
        } else {
            NetworkKind.METERED
        }
    }

    /** Espace libre sur le volume de `filesDir`, là où les modèles sont écrits (story 8.4). */
    fun freeBytes(context: Context): Long = StatFs(context.filesDir.path).availableBytes
}
