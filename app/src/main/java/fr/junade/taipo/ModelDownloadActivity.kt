package fr.junade.taipo

import android.app.ActivityManager
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.model.ActiveModelAfterDeletion
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelFileResolver
import fr.junade.taipo.model.ModelLicenseAcceptance
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.ModelRecommendation
import fr.junade.taipo.model.license
import fr.junade.taipo.model.download.DownloadEnvironment
import fr.junade.taipo.model.download.DownloadPolicy
import fr.junade.taipo.model.download.DownloadState
import fr.junade.taipo.model.download.DownloadTracker
import fr.junade.taipo.model.download.ModelDownloadScheduler
import fr.junade.taipo.model.download.ModelRowStatus

/**
 * Écran « Modèle IA » (épopée 8, story 8.1) : liste les 4 modèles de texte avec leur statut, permet de télécharger un
 * modèle (Wi-Fi par défaut, confirmation en données mobiles, contrôle d'espace libre) et de choisir le modèle
 * actif. L'écran de fichier fourni à la main reste dans [ModelSettingsActivity] (« Modèle IA local »).
 *
 * Le modèle vocal (dictée) a son propre écran : [VoiceModelDownloadActivity].
 *
 * Les lignes se mettent à jour par [DownloadTracker] : le téléchargement continue (service au premier plan) si
 * l'écran est quitté.
 */
class ModelDownloadActivity : ModelScreenActivity() {

    private lateinit var preferences: ModelPreferences
    private lateinit var licenseAcceptance: ModelLicenseAcceptance

    /** Modèle recommandé pour la RAM de l'appareil (story 8.2) : indicatif, n'empêche aucun téléchargement. */
    private lateinit var recommendedModel: AiModel
    private val rows = LinkedHashMap<AiModel, ModelRowViews>()

    /** Modèles en cours de suppression (story 8.13) : la ligne n'offre aucune action tant qu'elle n'est pas finie. */
    private val deleting = HashSet<AiModel>()

    private val trackerListener = DownloadTracker.Listener { model, _ ->
        runOnUiThread { if (!isDestroyed) render(model) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_download)
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        preferences = ModelPreferences(this)
        licenseAcceptance = ModelLicenseAcceptance(this)
        val memoryInfo = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo)
        recommendedModel = ModelRecommendation.recommended(memoryInfo.totalMem)

        val container = findViewById<LinearLayout>(R.id.models_container)
        val inflater = LayoutInflater.from(this)
        AiModel.entriesForDisplay().forEach { model ->
            val item = inflater.inflate(R.layout.item_model_download, container, false)
            item.applyTaipoFontToTree()
            rows[model] = ModelRowViews.from(item)
            container.addView(item)
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadTracker.addListener(trackerListener)
        renderAll()
    }

    override fun onStop() {
        DownloadTracker.removeListener(trackerListener)
        super.onStop()
    }

    private fun renderAll() {
        AiModel.entriesOrdered().forEach { render(it) }
    }

    private fun render(model: AiModel) {
        val views = rows[model] ?: return
        val state = DownloadTracker.stateOf(model)
        val installed = preferences.isInstalled(model)
        val downloadable = DownloadPolicy.isDownloadable(model, allowUnverified = BuildConfig.DEBUG)
        val updateAvailable = installed && preferences.updateAvailable(model)
        val status = ModelRowStatus.of(installed, downloadable, state, updateAvailable)
        val present = status == ModelRowStatus.INSTALLED || status == ModelRowStatus.UPDATE_AVAILABLE
        val active = preferences.activeModel() == model

        views.name.text = model.displayName(this)
        views.subtitle.text = model.subtitle(this)
        views.subtitle.visibility = View.VISIBLE
        val size = Formatter.formatFileSize(this, model.approxDownloadBytes ?: model.approxSizeBytesMax)
        val technicalLine = getString(R.string.model_download_technical_line, model.technicalName, size)
        // Gemma 3 (Ultra-léger, Léger) n'a pas d'entrée audio : on prévient que certaines fonctions manquent.
        views.technical.text = if (model.hasAudioInput) {
            technicalLine
        } else {
            technicalLine + "\n" + getString(R.string.model_download_limited_features)
        }

        // Pastille : « Actif » pour le modèle choisi, « Installé » pour les autres modèles présents, sinon
        // « Recommandé » sur le modèle conseillé pour la RAM (8.2), s'il n'est pas déjà installé.
        val badgeLabel = when {
            present && active -> R.string.model_download_badge_active
            present -> R.string.model_download_badge_installed
            model == recommendedModel -> R.string.model_download_badge_recommended
            else -> null
        }
        views.badge.visibility = if (badgeLabel != null) View.VISIBLE else View.GONE
        if (badgeLabel != null) {
            views.badge.setText(badgeLabel)
            views.badge.setBackgroundResource(R.drawable.bg_status_badge_ready)
            views.badge.setTextColor(getColor(R.color.text_primary))
        }

        views.progress.visibility = View.GONE
        views.primary.visibility = View.GONE
        views.secondary.visibility = View.GONE
        views.tertiary.visibility = View.GONE

        when (status) {
            ModelRowStatus.UNAVAILABLE -> {
                views.status.setText(R.string.model_download_status_unavailable)
            }

            ModelRowStatus.DOWNLOAD -> {
                views.status.text = if (BuildConfig.DEBUG && model.sha256 == null) {
                    getString(R.string.model_download_status_debug_unverified)
                } else {
                    getString(R.string.model_download_status_not_installed)
                }
                showPrimary(views, R.string.model_download_action_download) { onDownloadClicked(model) }
            }

            ModelRowStatus.DOWNLOADING -> {
                val downloading = state as DownloadState.Downloading
                val percent = downloading.percent
                views.status.text = if (percent != null) {
                    getString(R.string.model_download_status_downloading_percent, percent)
                } else {
                    getString(R.string.model_download_status_downloading)
                }
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = percent == null
                if (percent != null) views.progress.progress = percent
                showSecondary(views, R.string.model_download_action_cancel) {
                    ModelDownloadScheduler.cancel(this, model)
                }
            }

            ModelRowStatus.VERIFYING -> {
                views.status.setText(R.string.model_download_status_verifying)
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = true
            }

            ModelRowStatus.RETRY -> {
                val reason = (state as DownloadState.Failed).reason
                views.status.setText(failureMessage(reason))
                showPrimary(views, R.string.model_download_action_retry) { onDownloadClicked(model) }
            }

            ModelRowStatus.INSTALLED, ModelRowStatus.UPDATE_AVAILABLE -> if (model in deleting) {
                views.status.setText(R.string.model_download_status_deleting)
                views.progress.visibility = View.VISIBLE
                views.progress.isIndeterminate = true
            } else {
                // Story 8.12 : un modèle chargé à la main (debug) n'a pas passé la vérification SHA-256 du catalogue.
                views.status.setText(
                    when {
                        LocalModelAccess.showsAsLocalFile(BuildConfig.DEBUG, preferences.isDownloaded(model)) ->
                            R.string.model_download_status_local_file
                        // Story 8.14 : après un échec de mise à jour, le message d'échec remplace l'invite.
                        updateAvailable && state is DownloadState.Failed -> failureMessage(state.reason)
                        updateAvailable -> R.string.model_download_status_update_available
                        active -> R.string.model_download_status_installed_active
                        else -> R.string.model_download_status_installed
                    },
                )
                // Le modèle installé reste utilisable tant que la mise à jour n'est pas lancée (8.14) ; elle passe par
                // le même téléchargement sûr (8.9) : le nouveau fichier remplace l'ancien seulement une fois vérifié.
                val actions = mutableListOf<Pair<Int, () -> Unit>>()
                if (updateAvailable) actions += R.string.model_download_action_update to { onDownloadClicked(model) }
                if (!active) {
                    actions += R.string.model_download_action_use to {
                        preferences.setActiveModel(model)
                        renderAll()
                    }
                }
                actions.forEachIndexed { index, (label, onClick) ->
                    if (index == 0) showPrimary(views, label, onClick) else showSecondary(views, label, onClick)
                }
                val delete = { confirmDelete(model) }
                if (actions.size >= 2) {
                    showTertiary(views, R.string.model_download_action_delete, delete)
                } else {
                    showSecondary(views, R.string.model_download_action_delete, delete)
                }
            }
        }
    }

    /** Tap sur « Télécharger » / « Réessayer » : contrôles réseau et espace (8.3, 8.4), puis démarrage. */
    private fun onDownloadClicked(model: AiModel) {
        // Story 8.11 : la mention de licence (Gemma 3) s'affiche avant tout téléchargement, une seule fois par licence.
        if (model.license.mustAskBeforeDownload(licenseAcceptance.isAccepted(model.license))) {
            showLicenseDialog(model)
            return
        }
        val decision = DownloadPolicy.decide(
            model = model,
            network = DownloadEnvironment.networkKind(this),
            freeBytes = DownloadEnvironment.freeBytes(this),
            mobileDataAccepted = false,
            allowUnverified = BuildConfig.DEBUG,
        )
        applyDecision(
            decision,
            start = { startDownload(model, mobileDataAccepted = false) },
            confirmMobile = {
                val size = Formatter.formatFileSize(this, model.approxDownloadBytes ?: model.approxSizeBytesMax)
                confirmMobileData(getString(R.string.model_download_mobile_message, model.displayName(this), size)) {
                    startDownload(model, mobileDataAccepted = true)
                }
            },
        )
    }

    /** Mention des Gemma Terms of Use et de la Prohibited Use Policy, avec liens ; « Accepter » relance le téléchargement. */
    private fun showLicenseDialog(model: AiModel) {
        val dialogContext = ContextThemeWrapper(this, R.style.MessageDialogTheme)
        val content = LayoutInflater.from(dialogContext).inflate(R.layout.dialog_model_license, null)
        content.applyTaipoFontToTree()
        content.findViewById<TextView>(R.id.license_dialog_message).text =
            getString(R.string.model_license_dialog_message, model.technicalName)
        content.findViewById<View>(R.id.license_dialog_link_terms).setOnClickListener {
            openWebLink(model.license.termsUrl)
        }
        content.findViewById<View>(R.id.license_dialog_link_policy).setOnClickListener {
            model.license.policyUrl?.let(::openWebLink)
        }
        content.findViewById<View>(R.id.license_dialog_link_conditions).setOnClickListener {
            startActivity(android.content.Intent(this, TermsActivity::class.java))
        }
        AlertDialog.Builder(dialogContext)
            .setTitle(getString(R.string.model_license_dialog_title, model.displayName(this)))
            .setView(content)
            .setPositiveButton(R.string.model_license_dialog_accept) { _, _ ->
                licenseAcceptance.accept(model.license)
                onDownloadClicked(model)
            }
            .setNegativeButton(R.string.model_license_dialog_cancel, null)
            .show()
    }

    /** Story 8.13 : confirmation, puis suppression. Aucune suppression automatique : seulement sur ce bouton. */
    private fun confirmDelete(model: AiModel) {
        val installed = AiModel.entriesOrdered().filter { preferences.isInstalled(it) }
        // Un modèle chargé à la main (debug) n'a pas forcément encore de copie interne : on retombe sur la taille du fichier choisi.
        val bytes = ModelFileResolver.localCopySize(this, model).takeIf { it > 0 }
            ?: preferences.savedFileSizeFor(model).coerceAtLeast(0L)
        val size = Formatter.formatFileSize(this, bytes)
        val successor = ActiveModelAfterDeletion.choose(model, preferences.activeModel(), installed)
        val message = when {
            installed.none { it != model } -> getString(R.string.model_delete_message_last, size)
            preferences.activeModel() == model && successor != null ->
                getString(R.string.model_delete_message_active, size, successor.displayName(this))
            else -> getString(R.string.model_delete_message, size)
        }
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setTitle(getString(R.string.model_delete_title, model.displayName(this)))
            .setMessage(message)
            .setPositiveButton(R.string.model_delete_confirm) { _, _ -> deleteModel(model) }
            .setNegativeButton(R.string.model_delete_cancel, null)
            .show()
    }

    /**
     * Libère d'abord le moteur (dans tout le processus, y compris celui du clavier) pour que le fichier supprimé ne
     * reste pas utilisé en mémoire, puis supprime le fichier, son cache et la référence. Hors du thread principal.
     */
    private fun deleteModel(model: AiModel) {
        if (!deleting.add(model)) return
        render(model)
        val appContext = applicationContext
        val modelPreferences = preferences
        Thread({
            var failure = false
            var freed = 0L
            try {
                LlmEngineHost.releaseModelEverywhere(model)
                freed = modelPreferences.clearAndDeleteCopy(model)
            } catch (e: Exception) {
                AppLog.w(TAG, "Suppression du modèle impossible", e)
                failure = true
            }
            // Le modèle actif suit : un autre modèle installé le remplace, sinon plus d'actif (8.5 s'applique).
            val installed = AiModel.entriesOrdered().filter { modelPreferences.isInstalled(it) }
            val active = modelPreferences.activeModel()
            if (active == model) {
                val next = ActiveModelAfterDeletion.choose(model, active, installed)
                if (next != null) modelPreferences.setActiveModel(next) else modelPreferences.clearActiveModel()
            }
            runOnUiThread {
                deleting.remove(model)
                if (isDestroyed) return@runOnUiThread
                renderAll()
                val text = if (failure) {
                    getString(R.string.model_delete_failed)
                } else {
                    getString(R.string.model_delete_done, Formatter.formatFileSize(appContext, freed))
                }
                Toast.makeText(this, text, Toast.LENGTH_LONG).show()
            }
        }, "taipo-model-delete").start()
    }

    private fun startDownload(model: AiModel, mobileDataAccepted: Boolean) =
        launchWithNotificationPermission { ModelDownloadScheduler.start(this, model, mobileDataAccepted) }

    private companion object {
        private const val TAG = "ModelDownloadActivity"
    }
}
