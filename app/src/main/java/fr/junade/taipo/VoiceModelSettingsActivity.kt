package fr.junade.taipo

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.model.VoiceModelFile
import fr.junade.taipo.model.VoiceModelPreferences

/**
 * Écran de sélection des 4 fichiers du modèle vocal (encoder/decoder/joiner/
 * tokens.txt). Comme pour les modèles de texte, pas de téléchargement dans ce
 * prototype : chaque fichier est fourni manuellement par l'utilisateur via le
 * sélecteur de fichiers du téléphone.
 */
class VoiceModelSettingsActivity : ComponentActivity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    private lateinit var preferences: VoiceModelPreferences
    private val statusViews = mutableMapOf<VoiceModelFile, TextView>()

    /**
     * Lot 3.5 (U3) : un sélecteur de fichier par fichier du modèle (Activity Result API), enregistrés dans
     * `onCreate` dans un ordre fixe pour que le résultat retrouve son fichier même après un redémarrage
     * du processus.
     */
    private val filePickers = mutableMapOf<VoiceModelFile, ActivityResultLauncher<Array<String>>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_model_settings)
        applySystemBarInsets()
        preferences = VoiceModelPreferences(this)

        VoiceModelFile.all().forEach { file ->
            filePickers[file] = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                // Annulation du sélecteur : uri == null, rien à faire.
                if (uri != null) onFilePicked(file, uri)
            }
        }

        val container = findViewById<LinearLayout>(R.id.voice_model_file_list)
        VoiceModelFile.all().forEach { file -> container.addView(buildRow(file)) }
    }

    private fun buildRow(file: VoiceModelFile): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
        }

        val title = TextView(this).apply {
            text = "${file.label} — ${file.filenameHint}"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(title)

        val status = TextView(this).apply {
            text = statusText(file)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        }
        statusViews[file] = status
        row.addView(status)

        val button = Button(this).apply {
            text = getString(R.string.voice_model_pick_file)
            gravity = Gravity.CENTER
        }
        button.setOnClickListener { openFilePickerFor(file) }
        row.addView(button)

        return row
    }

    private fun statusText(file: VoiceModelFile): String {
        val name = preferences.savedFileNameFor(file)
        return if (name != null) {
            getString(R.string.voice_model_current_file, name)
        } else {
            getString(R.string.voice_model_no_file)
        }
    }

    private fun openFilePickerFor(file: VoiceModelFile) {
        filePickers.getValue(file).launch(arrayOf("*/*"))
    }

    private fun onFilePicked(file: VoiceModelFile, uri: Uri) {
        val fileName = queryDisplayName(uri)
        preferences.assignUri(file, uri, fileName)
        statusViews[file]?.text = statusText(file)
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = it.getString(nameIndex)
            }
        }
        return name
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
