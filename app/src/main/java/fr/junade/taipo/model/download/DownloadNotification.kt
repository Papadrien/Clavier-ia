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

    fun foregroundInfo(
        context: Context,
        notificationId: Int,
        title: String,
        percent: Int?,
        cancel: PendingIntent,
        screen: Class<*> = ModelDownloadActivity::class.java,
    ): ForegroundInfo =
        ForegroundInfo(
            notificationId,
            build(context, title, percent, cancel, screen),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    fun notifyProgress(
        context: Context,
        notificationId: Int,
        title: String,
        percent: Int,
        cancel: PendingIntent,
        screen: Class<*> = ModelDownloadActivity::class.java,
    ) {
        context.getSystemService(NotificationManager::class.java)
            ?.notify(notificationId, build(context, title, percent, cancel, screen))
    }

    /** [screen] : écran ouvert au toucher de la notification (« Modèle IA » ou « Modèle vocal »). */
    /**
     * Audit UX-05 : fin du téléchargement, réussi ou en échec (pas après une annulation par l'utilisateur). La notification de
     * progression disparaît avec le travail : celle-ci, sous un autre identifiant, reste jusqu'à ce qu'on la touche.
     */
    fun notifyFinished(context: Context, notificationId: Int, success: Boolean, modelName: String, screen: Class<*>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(
                context.getString(if (success) R.string.model_download_done_title else R.string.model_download_failed_title, modelName),
            )
            .setContentText(context.getString(if (success) R.string.model_download_done_text else R.string.model_download_failed_text))
            .setContentIntent(openScreen(context, screen))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notificationId + FINISHED_ID_OFFSET, notification) } // refusée si les notifications sont coupées
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.model_download_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun openScreen(context: Context, screen: Class<*>): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val FINISHED_ID_OFFSET = 100

    private fun build(context: Context, title: String, percent: Int?, cancel: PendingIntent, screen: Class<*>): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.let { ensureChannel(context, it) }
        val openScreen = openScreen(context, screen)
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
