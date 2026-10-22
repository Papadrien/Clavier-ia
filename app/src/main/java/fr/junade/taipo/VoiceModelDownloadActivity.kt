package fr.junade.taipo

import android.os.Bundle
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.Toast
import fr.junade.taipo.ai.VoiceEngine
import fr.junade.taipo.model.VoiceModelFile
import fr.junade.taipo.model.VoiceModelFileResolver
import fr.junade.taipo.model.VoiceModelPreferences
import fr.junade.taipo.model.download.DownloadEnvironment
import fr.junade.taipo.model.download.DownloadPolicy
import fr.junade.taipo.model.download.DownloadState
import fr.junade.taipo.model.download.DownloadTracker
import fr.junade.taipo.model.download.ModelDownloadScheduler
import fr.junade.taipo.model.download.ModelRowStatus
import fr.junade.taipo.model.download.VoiceModelDownload

/**
 * Écran « Modèle vocal » (story 8.15) : télécharge le modèle de dictée (bouton Vocal), séparé de l'écran « Modèle IA »
 * ([ModelDownloadActivity]). Mêmes statuts et même mécanisme de téléchargement que les modèles de texte ; ses 4 fichiers
 * (encoder, decoder, joiner, tokens) sont téléchargés comme un seul modèle (pas de « modèle actif » : il n'y en a qu'un).
 * Ouvert depuis la ligne « Modèle vocal » de l'accueil, depuis le bouton Vocal quand le modèle est absent (8.5) ou depuis
 * la notification de téléchargement.
 */
class VoiceModelDownloadActivity : ModelScreenActivity() {

    private lateinit var voicePreferences: VoiceModelPreferences
    private var voiceRow: ModelRowViews? = null

    /** Suppression du modèle vocal en cours (story 8.15) : la ligne n'offre aucune action tant qu'elle n'est pas finie. */
    private var deletingVoice = false

    private val voiceTrackerListener = DownloadTracker.VoiceListener {
        runOnUiThread { if (!isDestroyed) renderVoice() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_model_download)
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        voicePreferences = VoiceModelPreferences(this)

        val voiceContainer = findViewById<LinearLayout>(R.id.voice_container)
        val voiceItem = LayoutInflater.from(this).inflate(R.layout.item_model_download, voiceContainer, false)
        voiceItem.applyTaipoFontToTree()
        voiceRow = ModelRowViews.from(voiceItem)
        voiceContainer.addView(voiceItem)
    }

    override fun onStart() {
        super.onStart()
        DownloadTracker.addVoiceListener(voiceTrackerListener)
        renderVoice()
    }

    override fun onStop() {
        DownloadTracker.removeVoiceListener(voiceTrackerListener)
        super.onStop()
    }

    /**
     * Ligne du modèle vocal. Mêmes statuts que les modèles de texte ([ModelRowStatus]) ; pas de « modèle
     * actif » (il n'y en a qu'un) ni de mention de licence (voir docs/telechargement-modeles.md).
     */
    private fun renderVoice() {
        val views = voiceRow ?: return
        val state = DownloadTracker.voiceStateOf()
        val installed = voicePreferences.isComplete()
        val downloadable = VoiceModelDownload.isDownloadable(allowUnverified = BuildConfig.DEBUG)
        val updateAvailable = installed && voicePreferences.updateAvailable()
        val status = ModelRowStatus.of(installed, downloadable, state, updateAvailable)
        val present = status == ModelRowStatus.INSTALLED || status == ModelRowStatus.UPDATE_AVAILABLE

        views.name.setText(R.string.model_download_voice_name)
        views.technical.text = getString(
            R.string.model_download_technical_line,
            getString(R.string.model_download_voice_technical_name),
            Formatter.formatFileSize(this, VoiceModelFile.totalApproxBytes()),
        )
        views.badge.visibility = if (present) View.VISIBLE else View.GONE
        if (present) {
            views.badge.setText(R.string.model_download_badge_installed)
            views.badge.setBackgroundResource(R.drawable.bg_status_badge_ready)
            views.badge.setTextColor(getColor(R.color.text_primary))
        }

        views.progress.visibility = View.GONE
        views.primary.visibility = View.GONE
        views.secondary.visibility = View.GONE
        views.tertiary.visibility = View.GONE

        when (status) {
            ModelRowStatus.UNAVAILABLE -> views.status.setText(R.string.model_download_status_unavailable)

            ModelRowStatus.DOWNLOAD -> {
                // Un modèle vocal incomplet (quelques fichiers seulement) se télécharge à nouveau pour combler le manque.
                views.status.text = when {
                    BuildConfig.DEBUG && VoiceModelFile.all().any { it.sha256 == null } ->
                        getString(R.string.model_download_voice_status_debug_unverified)
                    else -> getString(R.string.model_download_status_not_installed)
                }
                showPrimary(views, R.string.model_download_action_download) { onVoiceDownloadClicked() }
            }

            ModelRowStatus.DOWNLOADING -> {
                val percent = (state as DownloadState.Downloading).percent
                views.status.text = if (percent != null) {
                    getString(R.string.model_download_status_downloading_percent, percent)
                } else {
                    getString(R.string.model_download_status_downloading)
                }
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = percent == null
                if (percent != null) views.progress.progress = percent
                showSecondary(views, R.string.model_download_action_cancel) {
                    ModelDownloadScheduler.cancelVoice(this)
                }
            }

            ModelRowStatus.VERIFYING -> {
                views.status.setText(R.string.model_download_status_verifying)
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = true
            }

            ModelRowStatus.RETRY -> {
                views.status.setText(failureMessage((state as DownloadState.Failed).reason))
                showPrimary(views, R.string.model_download_action_retry) { onVoiceDownloadClicked() }
            }

            ModelRowStatus.INSTALLED, ModelRowStatus.UPDATE_AVAILABLE -> if (deletingVoice) {
                views.status.setText(R.string.model_download_status_deleting)
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = true
            } else {
                views.status.setText(
                    when {
                        // Story 8.12 : des fichiers chargés à la main (debug) n'ont pas passé la vérification SHA-256 du catalogue.
                        LocalModelAccess.showsAsLocalFile(BuildConfig.DEBUG, !voicePreferences.hasLocalFile()) ->
                            R.string.model_download_status_local_file
                        // Après un échec de mise à jour, le message d'échec remplace l'invite.
                        updateAvailable && state is DownloadState.Failed -> failureMessage(state.reason)
                        updateAvailable -> R.string.model_download_status_update_available
                        else -> R.string.model_download_status_installed
                    },
                )
                if (updateAvailable) showPrimary(views, R.string.model_download_action_update) { onVoiceDownloadClicked() }
                showSecondary(views, R.string.model_download_action_delete) { confirmDeleteVoice() }
            }
        }
    }

    /** Story 8.15 : « Télécharger » / « Réessayer » / « Mettre à jour » du modèle vocal : mêmes contrôles réseau et espace (8.3, 8.4). */
    private fun onVoiceDownloadClicked() {
        val pending = VoiceModelFile.all().filter {
            VoiceModelDownload.needsDownload(
                downloaded = voicePreferences.isDownloaded(it),
                present = voicePreferences.isFilePresent(it),
                updateAvailable = voicePreferences.updateAvailable(it),
            )
        }
        val missing = VoiceModelDownload.missingBytes(pending)
        val decision = DownloadPolicy.decideVoice(
            missingBytes = missing,
            network = DownloadEnvironment.networkKind(this),
            freeBytes = DownloadEnvironment.freeBytes(this),
            mobileDataAccepted = false,
            allowUnverified = BuildConfig.DEBUG,
        )
        applyDecision(
            decision,
            start = { startVoiceDownload(mobileDataAccepted = false) },
            confirmMobile = {
                val size = Formatter.formatFileSize(this, missing)
                confirmMobileData(getString(R.string.model_download_voice_mobile_message, size)) {
                    startVoiceDownload(mobileDataAccepted = true)
                }
            },
        )
    }

    /** Story 8.15 : confirmation, puis suppression des 4 fichiers du modèle vocal (jamais automatique). */
    private fun confirmDeleteVoice() {
        val bytes = VoiceModelFile.all().sumOf { file ->
            VoiceModelFileResolver.localFileFor(this, file).let { if (it.isFile) it.length() else 0L }
        }
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setTitle(R.string.model_delete_voice_title)
            .setMessage(getString(R.string.model_delete_voice_message, Formatter.formatFileSize(this, bytes)))
            .setPositiveButton(R.string.model_delete_confirm) { _, _ -> deleteVoiceModel() }
            .setNegativeButton(R.string.model_delete_cancel, null)
            .show()
    }

    /**
     * Ferme d'abord le moteur de dictée (dans tout le processus, clavier compris), puis supprime les fichiers et leurs
     * références, hors du thread principal. Le bouton Vocal redirige ensuite vers cet écran (8.5).
     */
    private fun deleteVoiceModel() {
        if (deletingVoice) return
        deletingVoice = true
        renderVoice()
        val appContext = applicationContext
        val preferences = voicePreferences
        Thread({
            var failure = false
            var freed = 0L
            try {
                VoiceEngine.releaseEverywhere()
                freed = preferences.clearAndDeleteAll()
            } catch (e: Exception) {
                AppLog.w(TAG, "Suppression du modèle vocal impossible", e)
                failure = true
            }
            runOnUiThread {
                deletingVoice = false
                if (isDestroyed) return@runOnUiThread
                renderVoice()
                val text = if (failure) {
                    getString(R.string.model_delete_failed)
                } else {
                    getString(R.string.model_delete_done, Formatter.formatFileSize(appContext, freed))
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }, "taipo-voice-model-delete").start()
    }

    private fun startVoiceDownload(mobileDataAccepted: Boolean) =
        launchWithNotificationPermission { ModelDownloadScheduler.startVoice(this, mobileDataAccepted) }

    private companion object {
        private const val TAG = "VoiceModelDownloadActivity"
    }
}
