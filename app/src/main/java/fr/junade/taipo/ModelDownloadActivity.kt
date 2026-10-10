package fr.junade.taipo

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.ai.VoiceEngine
import fr.junade.taipo.model.ActiveModelAfterDeletion
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelLicense
import fr.junade.taipo.model.ModelFileResolver
import fr.junade.taipo.model.ModelLicenseAcceptance
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.ModelRecommendation
import fr.junade.taipo.model.VoiceModelFile
import fr.junade.taipo.model.VoiceModelFileResolver
import fr.junade.taipo.model.VoiceModelPreferences
import fr.junade.taipo.model.license
import fr.junade.taipo.model.download.DownloadDecision
import fr.junade.taipo.model.download.DownloadEnvironment
import fr.junade.taipo.model.download.DownloadFailure
import fr.junade.taipo.model.download.DownloadPolicy
import fr.junade.taipo.model.download.DownloadState
import fr.junade.taipo.model.download.DownloadTracker
import fr.junade.taipo.model.download.ModelDownloadScheduler
import fr.junade.taipo.model.download.ModelRowStatus
import fr.junade.taipo.model.download.VoiceModelDownload

/**
 * Écran « Modèle IA » (épopée 8, story 8.1) : liste les 4 modèles avec leur statut, permet de télécharger un
 * modèle (Wi-Fi par défaut, confirmation en données mobiles, contrôle d'espace libre) et de choisir le modèle
 * actif. L'écran de fichier fourni à la main reste dans [ModelSettingsActivity] (« Modèle IA local »).
 *
 * Story 8.15 : une section « Modèle vocal » (dictée) en bas de l'écran, avec les mêmes statuts et le même mécanisme
 * de téléchargement ; ses 4 fichiers sont téléchargés comme un seul modèle (pas de « modèle actif » : il n'y en a qu'un).
 *
 * Les lignes se mettent à jour par [DownloadTracker] : le téléchargement continue (service au premier plan) si
 * l'écran est quitté.
 */
class ModelDownloadActivity : ComponentActivity() {

    private class RowViews(
        val name: TextView,
        val badge: TextView,
        val technical: TextView,
        val license: TextView,
        val status: TextView,
        val progress: ProgressBar,
        val primary: Button,
        val secondary: Button,
        val tertiary: Button,
    )

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    private lateinit var preferences: ModelPreferences
    private lateinit var licenseAcceptance: ModelLicenseAcceptance

    /** Modèle recommandé pour la RAM de l'appareil (story 8.2) : indicatif, n'empêche aucun téléchargement. */
    private lateinit var recommendedModel: AiModel
    private val rows = LinkedHashMap<AiModel, RowViews>()

    /** Modèles en cours de suppression (story 8.13) : la ligne n'offre aucune action tant qu'elle n'est pas finie. */
    private val deleting = HashSet<AiModel>()

    private lateinit var voicePreferences: VoiceModelPreferences
    private var voiceRow: RowViews? = null

    /** Suppression du modèle vocal en cours (story 8.15) : la ligne n'offre aucune action tant qu'elle n'est pas finie. */
    private var deletingVoice = false

    /** Démarrage en attente de la réponse à la demande de notification (Android 13+). */
    private var pendingStart: (() -> Unit)? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Accordée ou non, le téléchargement démarre : sans permission il tourne sans notification visible.
            pendingStart?.invoke()
            pendingStart = null
        }

    private val trackerListener = DownloadTracker.Listener { model, _ ->
        runOnUiThread { if (!isDestroyed) render(model) }
    }

    private val voiceTrackerListener = DownloadTracker.VoiceListener {
        runOnUiThread { if (!isDestroyed) renderVoice() }
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
        AiModel.entriesOrdered().forEach { model ->
            val item = inflater.inflate(R.layout.item_model_download, container, false)
            item.applyTaipoFontToTree()
            rows[model] = RowViews(
                name = item.findViewById(R.id.model_name),
                badge = item.findViewById(R.id.model_badge),
                technical = item.findViewById(R.id.model_technical),
                license = item.findViewById(R.id.model_license),
                status = item.findViewById(R.id.model_status),
                progress = item.findViewById(R.id.model_progress),
                primary = item.findViewById(R.id.model_primary),
                secondary = item.findViewById(R.id.model_secondary),
                tertiary = item.findViewById(R.id.model_tertiary),
            )
            container.addView(item)
        }

        // Story 8.15 : ligne du modèle vocal, sous les modèles de texte.
        voicePreferences = VoiceModelPreferences(this)
        val voiceContainer = findViewById<LinearLayout>(R.id.voice_container)
        val voiceItem = inflater.inflate(R.layout.item_model_download, voiceContainer, false)
        voiceItem.applyTaipoFontToTree()
        voiceRow = RowViews(
            name = voiceItem.findViewById(R.id.model_name),
            badge = voiceItem.findViewById(R.id.model_badge),
            technical = voiceItem.findViewById(R.id.model_technical),
            license = voiceItem.findViewById(R.id.model_license),
            status = voiceItem.findViewById(R.id.model_status),
            progress = voiceItem.findViewById(R.id.model_progress),
            primary = voiceItem.findViewById(R.id.model_primary),
            secondary = voiceItem.findViewById(R.id.model_secondary),
            tertiary = voiceItem.findViewById(R.id.model_tertiary),
        )
        voiceContainer.addView(voiceItem)

        // Ouvert depuis le bouton Vocal sans modèle vocal (8.5) ou depuis la ligne « Modèle vocal » de l'accueil.
        if (intent.getBooleanExtra(EXTRA_FOCUS_VOICE, false) && savedInstanceState == null) {
            val scroll = findViewById<ScrollView>(R.id.model_download_scroll)
            scroll.post { scroll.smoothScrollTo(0, findViewById<View>(R.id.voice_section_title).top) }
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadTracker.addListener(trackerListener)
        DownloadTracker.addVoiceListener(voiceTrackerListener)
        renderAll()
    }

    override fun onStop() {
        DownloadTracker.removeListener(trackerListener)
        DownloadTracker.removeVoiceListener(voiceTrackerListener)
        super.onStop()
    }

    private fun renderAll() {
        AiModel.entriesOrdered().forEach { render(it) }
        renderVoice()
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

        views.name.text = model.displayName
        val size = Formatter.formatFileSize(this, model.approxDownloadBytes ?: model.approxSizeBytesMax)
        views.technical.text = getString(R.string.model_download_technical_line, model.technicalName, size)
        views.license.setText(
            when (model.license) {
                ModelLicense.GEMMA_TERMS -> R.string.model_download_license_gemma
                ModelLicense.APACHE_2 -> R.string.model_download_license_apache
            },
        )

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

    /**
     * Story 8.15 : ligne du modèle vocal. Mêmes statuts que les modèles de texte ([ModelRowStatus]) ; pas de « modèle
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
        views.license.visibility = View.GONE
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

    private fun showPrimary(views: RowViews, label: Int, onClick: () -> Unit) {
        views.primary.setText(label)
        views.primary.setOnClickListener { onClick() }
        views.primary.visibility = View.VISIBLE
    }

    private fun showTertiary(views: RowViews, label: Int, onClick: () -> Unit) {
        views.tertiary.setText(label)
        views.tertiary.setOnClickListener { onClick() }
        views.tertiary.visibility = View.VISIBLE
    }

    private fun showSecondary(views: RowViews, label: Int, onClick: () -> Unit) {
        views.secondary.setText(label)
        views.secondary.setOnClickListener { onClick() }
        views.secondary.visibility = View.VISIBLE
    }

    private fun failureMessage(reason: DownloadFailure): Int = when (reason) {
        DownloadFailure.NETWORK -> R.string.model_download_failed_network
        DownloadFailure.SERVER -> R.string.model_download_failed_server
        DownloadFailure.NO_SPACE -> R.string.model_download_failed_no_space
        DownloadFailure.CORRUPTED -> R.string.model_download_failed_corrupted
        DownloadFailure.OTHER -> R.string.model_download_failed_other
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
                confirmMobileData(getString(R.string.model_download_mobile_message, model.displayName, size)) {
                    startDownload(model, mobileDataAccepted = true)
                }
            },
        )
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

    /** Réponse à la décision de [DownloadPolicy] : démarrage, confirmation données mobiles, ou message clair (8.3, 8.4). */
    private fun applyDecision(decision: DownloadDecision, start: () -> Unit, confirmMobile: () -> Unit) {
        when (decision) {
            DownloadDecision.Start -> start()
            DownloadDecision.ConfirmMobileData -> confirmMobile()
            DownloadDecision.NoConnection -> toast(R.string.model_download_toast_no_connection)
            DownloadDecision.NotAvailable -> toast(R.string.model_download_toast_not_available)
            is DownloadDecision.InsufficientSpace -> Toast.makeText(
                this,
                getString(
                    R.string.model_download_toast_no_space,
                    Formatter.formatFileSize(this, decision.requiredBytes),
                    Formatter.formatFileSize(this, decision.freeBytes),
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
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
            .setTitle(getString(R.string.model_license_dialog_title, model.displayName))
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
                getString(R.string.model_delete_message_active, size, successor.displayName)
            else -> getString(R.string.model_delete_message, size)
        }
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setTitle(getString(R.string.model_delete_title, model.displayName))
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

    private fun confirmMobileData(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setTitle(R.string.model_download_mobile_title)
            .setMessage(message)
            .setPositiveButton(R.string.model_download_mobile_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.model_download_mobile_cancel, null)
            .show()
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

    private fun startDownload(model: AiModel, mobileDataAccepted: Boolean) =
        launchWithNotificationPermission { ModelDownloadScheduler.start(this, model, mobileDataAccepted) }

    private fun startVoiceDownload(mobileDataAccepted: Boolean) =
        launchWithNotificationPermission { ModelDownloadScheduler.startVoice(this, mobileDataAccepted) }

    /** Lance [launch] ; sur Android 13+, la permission de notification est d'abord demandée, une seule fois. */
    private fun launchWithNotificationPermission(launch: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !hasAskedNotificationPermission()
        if (needsPermission) {
            markNotificationPermissionAsked()
            pendingStart = launch
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launch()
        }
    }

    private fun hasAskedNotificationPermission(): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(KEY_NOTIFICATION_ASKED, false)

    private fun markNotificationPermissionAsked() {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    companion object {
        /** Extra booléen : faire défiler jusqu'à la section « Modèle vocal » à l'ouverture (story 8.15). */
        const val EXTRA_FOCUS_VOICE = "focus_voice"

        private const val TAG = "ModelDownloadActivity"
        private const val UI_PREFS = "model_download_ui"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}
