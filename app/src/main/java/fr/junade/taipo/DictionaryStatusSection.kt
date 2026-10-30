package fr.junade.taipo

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import fr.junade.taipo.dictionary.DictionaryFiles
import fr.junade.taipo.dictionary.download.DictionaryDownloadScheduler
import fr.junade.taipo.dictionary.download.DictionaryDownloadState
import fr.junade.taipo.dictionary.download.DictionaryDownloadTracker
import fr.junade.taipo.model.download.DownloadFailure

/**
 * Section « Dictionnaire » du détail d'une langue : la ligne de la langue avec l'état de son dictionnaire (à télécharger, en
 * attente, téléchargement en %, installé, échec) et un bouton Réessayer. Le téléchargement démarre tout seul à l'ouverture
 * ([refresh]).
 */
internal class DictionaryStatusSection(
    private val activity: Activity,
    private val container: LinearLayout,
    private val language: KeyboardLanguage,
) {
    private class Row(val language: KeyboardLanguage, val view: View) {
        val status: TextView = view.findViewById(R.id.dictionary_status)
        val progress: ProgressBar = view.findViewById(R.id.dictionary_progress)
        val action: Button = view.findViewById(R.id.dictionary_action)
    }

    private var rows: List<Row> = emptyList()

    private val listener = DictionaryDownloadTracker.Listener { changed, _ ->
        activity.runOnUiThread { rows.firstOrNull { it.language == changed }?.let(::bind) }
    }

    /** Reconstruit la ligne si besoin et lance le téléchargement du dictionnaire s'il manque. */
    fun refresh() {
        if (rows.isEmpty()) {
            val view = LayoutInflater.from(activity).inflate(R.layout.item_dictionary_status, container, false)
            view.findViewById<TextView>(R.id.dictionary_name).setText(R.string.language_detail_dictionary_title)
            container.addView(view)
            rows = listOf(Row(language, view))
            container.applyTaipoFontToTree()
        }
        DictionaryDownloadScheduler.ensure(activity, language)
        rows.forEach(::bind)
    }

    fun start() {
        DictionaryDownloadTracker.addListener(listener)
        refresh()
    }

    fun stop() {
        DictionaryDownloadTracker.removeListener(listener)
    }

    private fun bind(row: Row) {
        val language = row.language
        val state = DictionaryDownloadTracker.stateOf(language)
        val installed = DictionaryFiles.isInstalled(activity.filesDir, language)
        row.progress.visibility = View.GONE
        row.action.visibility = View.GONE
        when {
            state is DictionaryDownloadState.Downloading -> {
                row.status.text = activity.getString(R.string.dictionary_status_downloading, state.percent)
                row.progress.isIndeterminate = false
                row.progress.progress = state.percent
                row.progress.visibility = View.VISIBLE
            }
            state == DictionaryDownloadState.Queued -> {
                row.status.setText(R.string.dictionary_status_queued)
                row.progress.isIndeterminate = true
                row.progress.visibility = View.VISIBLE
            }
            installed -> row.status.setText(R.string.dictionary_status_installed)
            state is DictionaryDownloadState.Failed -> {
                row.status.setText(failureMessage(state.reason))
                showAction(row, R.string.dictionary_retry)
            }
            else -> {
                row.status.setText(R.string.dictionary_status_missing)
                showAction(row, R.string.dictionary_download)
            }
        }
    }

    private fun showAction(row: Row, label: Int) {
        row.action.setText(label)
        row.action.setOnClickListener { DictionaryDownloadScheduler.retry(activity, row.language) }
        row.action.visibility = View.VISIBLE
    }

    private fun failureMessage(reason: DownloadFailure): Int = when (reason) {
        DownloadFailure.NETWORK -> R.string.dictionary_failed_network
        DownloadFailure.SERVER, DownloadFailure.CORRUPTED -> R.string.dictionary_failed_server
        DownloadFailure.NO_SPACE -> R.string.dictionary_failed_no_space
        DownloadFailure.SYSTEM_STOP -> R.string.dictionary_failed_system_stop
        DownloadFailure.OTHER -> R.string.dictionary_failed_other
    }
}
