package fr.papadrien.clavier

import android.app.Activity
import android.content.Intent
import android.database.Cursor
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
import fr.papadrien.clavier.ai.CorrectionPromptPreferences
import fr.papadrien.clavier.model.AiModel
import fr.papadrien.clavier.model.ModelPreferences
import fr.papadrien.clavier.model.sizeWarning

/**
 * Écran de sélection du modèle IA.
 *
 * Prototype : pas de téléchargement. La page ne contient que la sélection
 * du modèle (menu déroulant listant les 4 modèles Gemma définis) ; le choix
 * d'un modèle déclenche le sélecteur de fichiers du téléphone pour que
 * l'utilisateur fournisse lui-même le fichier .litertlm correspondant.
 */
class ModelSettingsActivity : Activity() {

    private lateinit var preferences: ModelPreferences
    private lateinit var promptPreferences: CorrectionPromptPreferences
    private lateinit var spinner: Spinner
    private lateinit var textModelInfo: TextView
    private lateinit var textCurrentFile: TextView
    private lateinit var textSizeWarning: TextView
    private lateinit var buttonForgetFile: Button
    private lateinit var editCorrectionPrompt: EditText
    private lateinit var textPromptStatus: TextView

    /** Modèle actuellement affiché dans la page (correspond à la sélection du spinner). */
    private var displayedModel: AiModel = AiModel.entriesOrdered().first()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)
        preferences = ModelPreferences(this)
        promptPreferences = CorrectionPromptPreferences(this)

        spinner = findViewById(R.id.spinner_model)
        textModelInfo = findViewById(R.id.text_model_info)
        textCurrentFile = findViewById(R.id.text_current_file)
        textSizeWarning = findViewById(R.id.text_size_warning)
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Aucun type MIME standard n'existe pour .litertlm : on laisse tout
            // ouvrir et on vérifie ensuite le nom/la taille du fichier choisi.
            type = "*/*"
        }
        startActivityForResult(intent, model.requestCode())
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val model = AiModel.entriesOrdered().firstOrNull { it.requestCode() == requestCode } ?: return

        val (fileName, fileSize) = queryNameAndSize(uri)
        preferences.assignUri(model, uri, fileName, fileSize)

        if (model == displayedModel) {
            refreshInfo()
        }
        Toast.makeText(this, getString(R.string.model_settings_file_saved, model.displayName), Toast.LENGTH_SHORT).show()
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.let {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = it.getString(nameIndex)
                    if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
                }
            }
        } finally {
            cursor?.close()
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

            val warning = model.sizeWarning(fileSize)
            if (warning != null) {
                textSizeWarning.text = warning
                textSizeWarning.visibility = View.VISIBLE
            } else {
                textSizeWarning.visibility = View.GONE
            }
        } else {
            textCurrentFile.text = getString(R.string.model_settings_no_file)
            buttonForgetFile.visibility = View.GONE
            textSizeWarning.visibility = View.GONE
        }
    }

    private fun refreshPromptStatus() {
        textPromptStatus.text = getString(
            if (promptPreferences.isCustom()) R.string.correction_prompt_status_custom
            else R.string.correction_prompt_status_default,
        )
    }

    /** Code de requête stable pour distinguer les 4 sélecteurs de fichier (onActivityResult). */
    private fun AiModel.requestCode(): Int = AiModel.entriesOrdered().indexOf(this) + 1
}
