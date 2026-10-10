package fr.junade.taipo.model.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import fr.junade.taipo.ModelDownloadActivity
import fr.junade.taipo.R

/**
 * Notification du service au premier plan des téléchargements (modèles de texte et modèle vocal) : un téléchargement
 * de plusieurs centaines de Mo ou de plusieurs Go doit continuer écran éteint. Partagée par les deux workers ; chacun
 * passe son propre identifiant pour que deux téléchargements simultanés ne s'écrasent pas.
 */
object DownloadNotification {

    const val CHANNEL_ID = "model_download"

    fun foregroundInfo(context: Context, notificationId: Int, title: String, percent: Int?, cancel: PendingIntent): ForegroundInfo =
        ForegroundInfo(
            notificationId,
            build(context, title, percent, cancel),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    fun notifyProgress(context: Context, notificationId: Int, title: String, percent: Int, cancel: PendingIntent) {
        context.getSystemService(NotificationManager::class.java)
            ?.notify(notificationId, build(context, title, percent, cancel))
    }

    private fun build(context: Context, title: String, percent: Int?, cancel: PendingIntent): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.model_download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val openScreen = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ModelDownloadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(openScreen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.model_download_cancel), cancel).build(),
            )
            .build()
    }
}
