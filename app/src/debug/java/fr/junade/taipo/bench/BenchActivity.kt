package fr.junade.taipo.bench

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Écran du banc d'essai de l'autocorrection (debug uniquement, icône « Taipo Bench »). Les résultats s'affichent
 * ici (texte sélectionnable, bouton « Copier ») et sont aussi écrits dans le logcat, tag « TaipoBench ».
 */
class BenchActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var startButton: Button
    private val lines = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // L'écran reste allumé pendant la mesure (quelques minutes).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            fitsSystemWindows = true
        }
        startButton = Button(this).apply {
            text = "Lancer le banc d'essai"
            setOnClickListener { start() }
        }
        val copyButton = Button(this).apply {
            text = "Copier les résultats"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Taipo Bench", lines.toString()))
                Toast.makeText(this@BenchActivity, "Résultats copiés", Toast.LENGTH_SHORT).show()
            }
        }
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = "Appuie sur « Lancer » et laisse l'écran allumé (quelques minutes).\nRésultats aussi dans le logcat, filtre : TaipoBench"
        }
        column.addView(startButton)
        column.addView(copyButton)
        column.addView(output)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun start() {
        startButton.isEnabled = false
        lines.setLength(0)
        output.text = ""
        Thread {
            SpellBench.execute(this) { line ->
                runOnUiThread {
                    lines.append(line).append('\n')
                    output.append(line + "\n")
                }
            }
            runOnUiThread { startButton.isEnabled = true }
        }.start()
    }
}
