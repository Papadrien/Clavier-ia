package fr.junade.taipo

import android.content.Context
import android.content.Intent

/**
 * Story 8.5 : ouvre l'écran « Modèle IA » (téléchargement) quand une action IA est demandée sans modèle utilisable.
 * Story 8.15 : [openVoice] ouvre le même écran, déjà défilé sur la section « Modèle vocal », quand Vocal est demandé
 * sans modèle vocal complet.
 *
 * Le clavier est un service : une activité ne peut y être lancée que dans une nouvelle tâche
 * ([Intent.FLAG_ACTIVITY_NEW_TASK]). Le texte déjà saisi (champ ou prompt) n'est pas touché.
 */
object ModelScreenRedirect {
    fun open(context: Context) {
        context.startActivity(
            Intent(context, ModelDownloadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openVoice(context: Context) {
        context.startActivity(voiceIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Intent de l'écran « Modèle IA » ouvert sur la section « Modèle vocal » (aussi utilisé par l'accueil, sans nouvelle tâche). */
    fun voiceIntent(context: Context): Intent =
        Intent(context, ModelDownloadActivity::class.java).putExtra(ModelDownloadActivity.EXTRA_FOCUS_VOICE, true)
}
