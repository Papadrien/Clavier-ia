package fr.junade.taipo.diagnostic

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.junade.taipo.KeyboardPreferences
import fr.junade.taipo.KeyboardTheme
import fr.junade.taipo.R
import fr.junade.taipo.applySystemBarInsets
import fr.junade.taipo.applyTaipoFontToTree
import fr.junade.taipo.automaticKeyboardLanguage
import fr.junade.taipo.showScreenMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran « Diagnostic mémoire » (builds debug, bouton en bas de l'accueil) : mesure, sur le téléphone, la mémoire et la vitesse
 * des dictionnaires des langues en service ([MemoryDiagnostic]) et permet de copier le résultat pour le partager. Sans
 * ordinateur, c'est le moyen de relever ces chiffres (le journal du système n'est lisible qu'avec un câble).
 */
class MemoryDiagnosticActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var output: TextView
    private lateinit var measure: Button

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = resources.getDimensionPixelSize(R.dimen.taipo_settings_screen_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_md)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        column.addView(TextView(this, null, 0, R.style.TaipoText_Title).apply { setText(R.string.diagnostic_title) })
        column.addView(
            TextView(this, null, 0, R.style.TaipoText_Caption).apply { setText(R.string.diagnostic_explanation) },
            params(gap),
        )
        measure = Button(this, null, 0, R.style.TaipoButton_Primary).apply {
            setText(R.string.diagnostic_measure)
            setOnClickListener { runMeasures() }
        }
        column.addView(measure, params(gap))
        column.addView(
            Button(this, null, 0, R.style.TaipoButton_Secondary).apply {
                setText(R.string.diagnostic_copy)
                setOnClickListener { copyResults() }
            },
            params(gap / 2),
        )
        output = TextView(this, null, 0, R.style.TaipoText_Info).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        column.addView(output, params(gap))
        setContentView(ScrollView(this).apply { addView(column) })
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        output.typeface = Typeface.MONOSPACE // la police Taipo posée juste avant : les chiffres restent alignés en monospace
        applySystemBarInsets()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun params(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun runMeasures() {
        if (job?.isActive == true) return
        measure.isEnabled = false
        output.text = getString(R.string.diagnostic_running)
        val languages = KeyboardPreferences(this).languageSetup.inUse(automaticKeyboardLanguage())
        val diagnostic = MemoryDiagnostic(applicationContext)
        job = scope.launch {
            val lines = StringBuilder()
            fun append(line: String) {
                lines.appendLine(line)
                output.text = lines
            }
            append(withContext(Dispatchers.Default) { diagnostic.deviceSummary() })
            for (language in languages) {
                try {
                    withContext(Dispatchers.Default) {
                        diagnostic.measure(language) { line -> launch(Dispatchers.Main) { append(line) } }
                    }
                } catch (e: OutOfMemoryError) {
                    append("${language.displayName} : mémoire insuffisante pendant la mesure (${e.javaClass.simpleName})")
                }
            }
            append(getString(R.string.diagnostic_done))
            measure.isEnabled = true
        }
    }

    private fun copyResults() {
        val text = output.text?.toString().orEmpty()
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostic_title), text))
        showScreenMessage(R.string.diagnostic_copied)
    }
}
