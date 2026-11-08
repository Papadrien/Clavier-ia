package fr.junade.taipo.model.download

import android.annotation.SuppressLint
import androidx.work.ListenableWorker
import androidx.work.WorkInfo

/**
 * L'arrêt du travail vient-il de l'application (annulation par l'utilisateur) ? WorkManager renseigne lui-même
 * `stopReason` à toutes les versions d'Android : c'est un champ du worker, sans appel au système (vérifié dans
 * work-runtime 2.10.0). L'annotation `@RequiresApi(31)` de la bibliothèque vient seulement des codes repris de
 * JobScheduler ; elle ne signale pas de plantage possible sous Android 12, d'où la suppression du lint ici, en un seul endroit.
 */
@SuppressLint("NewApi")
internal fun ListenableWorker.stoppedByApp(): Boolean = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP
