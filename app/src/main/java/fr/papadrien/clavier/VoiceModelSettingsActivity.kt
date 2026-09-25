package fr.papadrien.clavier

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import fr.papadrien.clavier.model.VoiceModelFile
import fr.papadrien.clavier.model.VoiceModelPreferences

/**
 * Écran de sélection des 4 fichiers du modèle vocal (encoder/decoder/joiner/
 * tokens.txt). Comme pour les modèles de texte, pas de téléchargement dans ce
 * prototype : chaque fichier est fourni manuellement par l'utilisateur via le
 * sélecteur de fichiers du téléphone.
 */
class VoiceModelSettingsActivity : Activity() {

    private lateinit var preferences: VoiceModelPreferences
    private val statusViews = mutableMapOf<VoiceModelFile, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_model_settings)
        preferences = VoiceModelPreferences(this)

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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, file.requestCode())
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val file = VoiceModelFile.all().firstOrNull { it.requestCode() == requestCode } ?: return

        val fileName = queryDisplayName(uri)
        preferences.assignUri(file, uri, fileName)
        statusViews[file]?.text = statusText(file)
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.let {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = it.getString(nameIndex)
                }
            }
        } finally {
            cursor?.close()
        }
        return name
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Code de requête stable pour distinguer les 4 sélecteurs de fichier (onActivityResult). */
    private fun VoiceModelFile.requestCode(): Int = VoiceModelFile.all().indexOf(this) + 1
}
