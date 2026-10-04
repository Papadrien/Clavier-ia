package fr.junade.taipo

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import fr.junade.taipo.ai.CorrectionPromptPreferences
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.checksumWarning
import fr.junade.taipo.model.sha256HexOrNull
import fr.junade.taipo.model.sizeWarning
import java.util.concurrent.ConcurrentHashMap

/**
 * Écran de sélection du modèle IA.
 *
 * Prototype : pas de téléchargement. La page ne contient que la sélection
 * du modèle (menu déroulant listant les 4 modèles Gemma définis) ; le choix
 * d'un modèle déclenche le sélecteur de fichiers du téléphone pour que
 * l'utilisateur fournisse lui-même le fichier .litertlm correspondant.
 */
class ModelSettingsActivity : ComponentActivity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    private lateinit var preferences: ModelPreferences
    private lateinit var promptPreferences: CorrectionPromptPreferences
    private lateinit var spinner: Spinner
    private lateinit var textModelInfo: TextView
    private lateinit var textCurrentFile: TextView
    private lateinit var textSizeWarning: TextView
    private lateinit var textChecksum: TextView
    private lateinit var buttonForgetFile: Button
    private lateinit var editCorrectionPrompt: EditText
    private lateinit var textPromptStatus: TextView

    /**
     * Calculs d'empreinte SHA-256 en cours (lot 3.4, S3) : modèle -> numéro d'exécution. Un nouveau choix de
     * fichier pour le même modèle remplace le numéro, ce qui annule l'ancien calcul. Écrit depuis le
     * thread principal, lu depuis le thread de calcul.
     */
    private val checksumRuns = ConcurrentHashMap<AiModel, Int>()
    private var nextChecksumRun = 0

    /** Modèle actuellement affiché dans la page (correspond à la sélection du spinner). */
    private var displayedModel: AiModel = AiModel.entriesOrdered().first()

    /**
     * Lot 3.5 (U3) : un sélecteur de fichier par modèle (Activity Result API). Ils sont enregistrés dans
     * `onCreate`, toujours dans le même ordre : le système peut ainsi rattacher le résultat au bon modèle
     * même si le processus a été tué pendant que le sélecteur était ouvert (pas de « modèle en attente » à
     * sauvegarder à la main, plus de code de requête).
     */
    private val filePickers = mutableMapOf<AiModel, ActivityResultLauncher<Array<String>>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)
        applySystemBarInsets()
        preferences = ModelPreferences(this)
        promptPreferences = CorrectionPromptPreferences(this)

        spinner = findViewById(R.id.spinner_model)
        textModelInfo = findViewById(R.id.text_model_info)
        textCurrentFile = findViewById(R.id.text_current_file)
        textSizeWarning = findViewById(R.id.text_size_warning)
        textChecksum = findViewById(R.id.text_checksum)
        buttonForgetFile = findViewById(R.id.button_forget_file)
        editCorrectionPrompt = findViewById(R.id.edit_correction_prompt)
        textPromptStatus = findViewById(R.id.text_prompt_status)

        editCorrectionPrompt.setText(promptPreferences.get())
        refreshPromptStatus()

        findViewById<Button>(R.id.button_save_prompt).setOnClickListener {
            val newPrompt = editCorrectionPrompt.text.toString()
            if (newPrompt.isBlank()) {
                promptPreferences.reset()
            } else {
                promptPreferences.set(newPrompt)
            }
            refreshPromptStatus()
            Toast.makeText(this, getString(R.string.correction_prompt_saved), Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.button_reset_prompt).setOnClickListener {
            promptPreferences.reset()
            editCorrectionPrompt.setText(promptPreferences.get())
            refreshPromptStatus()
        }

        val models = AiModel.entriesOrdered()
        models.forEach { model ->
            filePickers[model] = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                // Annulation du sélecteur : uri == null, rien à faire.
                if (uri != null) onModelFilePicked(model, uri)
            }
        }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            models.map { it.displayName },
        )

        val activeModel = preferences.activeModel()
        val initialIndex = activeModel?.let { models.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        spinner.setSelection(initialIndex)
        displayedModel = models[initialIndex]

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                displayedModel = models[position]
                preferences.setActiveModel(displayedModel)
                refreshInfo()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.button_pick_file).setOnClickListener {
            openFilePickerFor(displayedModel)
        }

        buttonForgetFile.setOnClickListener {
            preferences.clear(displayedModel)
            refreshInfo()
        }

        refreshInfo()
    }

    private fun openFilePickerFor(model: AiModel) {
        // Aucun type MIME standard n'existe pour .litertlm : on laisse tout ouvrir et on vérifie ensuite
        // le nom/la taille du fichier choisi. Le contrat OpenDocument ajoute lui-même CATEGORY_OPENABLE.
        filePickers.getValue(model).launch(arrayOf("*/*"))
    }

    private fun onModelFilePicked(model: AiModel, uri: Uri) {
        val (fileName, fileSize) = queryNameAndSize(uri)
        preferences.assignUri(model, uri, fileName, fileSize)

        if (model == displayedModel) {
            refreshInfo()
        }
        Toast.makeText(this, getString(R.string.model_settings_file_saved, model.displayName), Toast.LENGTH_SHORT).show()
        startChecksum(model, uri, fileSize)
    }

    /**
     * Lot 3.4 (S3) : calcule en arrière-plan l'empreinte SHA-256 du fichier choisi (plusieurs Go : ne jamais
     * le faire sur le thread principal) et l'enregistre, sauf si l'utilisateur a choisi un autre fichier
     * entre-temps. Le calcul continue si l'écran est quitté : il ne touche l'interface que si elle existe encore.
     */
    private fun startChecksum(model: AiModel, uri: Uri, totalBytes: Long) {
        val run = ++nextChecksumRun
        checksumRuns[model] = run
        refreshInfo()

        val appContext = applicationContext
        val modelPreferences = preferences
        Thread({
            var lastReportedAt = 0L
            val hash = try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    sha256HexOrNull(input, isCancelled = { checksumRuns[model] != run }) { read ->
                        if (read - lastReportedAt >= PROGRESS_STEP_BYTES) {
                            lastReportedAt = read
                            runOnUiThread { onChecksumProgress(model, run, read, totalBytes) }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Calcul de l'empreinte impossible", e)
                null
            }
            // Remplacé par un autre calcul : ce thread n'a plus rien à faire (l'autre gère l'affichage).
            if (checksumRuns[model] != run) return@Thread
            if (hash != null) modelPreferences.assignSha256(model, uri, hash)
            checksumRuns.remove(model, run)
            runOnUiThread { if (!isDestroyed && model == displayedModel) refreshInfo() }
        }, "taipo-model-sha256").start()
    }

    private fun onChecksumProgress(model: AiModel, run: Int, read: Long, totalBytes: Long) {
        if (isDestroyed || model != displayedModel || checksumRuns[model] != run) return
        val progress = if (totalBytes > 0) {
            getString(R.string.model_settings_checksum_progress, (read * 100 / totalBytes).coerceIn(0, 100).toInt())
        } else {
            "${read / 1_000_000} Mo"
        }
        textChecksum.text = getString(R.string.model_settings_checksum_running, progress)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private fun refreshInfo() {
        val model = displayedModel
        textModelInfo.text = getString(
            R.string.model_settings_model_info,
            model.technicalName,
            AiModel.EXPECTED_EXTENSION,
            model.fileHint,
            model.huggingFaceRepo,
        )

        val fileName = preferences.savedFileNameFor(model)
        val fileSize = preferences.savedFileSizeFor(model)
        if (fileName != null) {
            textCurrentFile.text = getString(R.string.model_settings_current_file, fileName)
            buttonForgetFile.visibility = View.VISIBLE

            val sha256 = preferences.savedSha256For(model)
            val warnings = listOfNotNull(model.sizeWarning(fileSize), sha256?.let { model.checksumWarning(it) })
            if (warnings.isNotEmpty()) {
                textSizeWarning.text = warnings.joinToString("\n\n")
                textSizeWarning.visibility = View.VISIBLE
            } else {
                textSizeWarning.visibility = View.GONE
            }

            textChecksum.text = when {
                sha256 != null -> getString(R.string.model_settings_checksum_value, sha256)
                checksumRuns.containsKey(model) -> getString(R.string.model_settings_checksum_running, "")
                else -> getString(R.string.model_settings_checksum_missing)
            }
            textChecksum.visibility = View.VISIBLE
        } else {
            textCurrentFile.text = getString(R.string.model_settings_no_file)
            buttonForgetFile.visibility = View.GONE
            textSizeWarning.visibility = View.GONE
            textChecksum.visibility = View.GONE
        }
    }

    private fun refreshPromptStatus() {
        textPromptStatus.text = getString(
            if (promptPreferences.isCustom()) R.string.correction_prompt_status_custom
            else R.string.correction_prompt_status_default,
        )
    }

    private companion object {
        const val TAG = "ModelSettingsActivity"

        /** Fréquence de mise à jour de la progression : tous les 64 Mo lus. */
        const val PROGRESS_STEP_BYTES = 64L * 1024 * 1024
    }
}
