package fr.junade.taipo

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import fr.junade.taipo.backup.BackupCodec
import fr.junade.taipo.backup.BackupData
import fr.junade.taipo.backup.BackupService
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran « Sauvegarde et restauration » (story 10.3, accueil > Mes données) : exporte le dictionnaire personnel, les éléments épinglés
 * du Smart Clipboard et les réglages dans un fichier JSON **non chiffré** (l'utilisateur en est averti avant), et restaure un tel
 * fichier en **fusionnant** avec les données de l'appareil. Le fichier se choisit et s'enregistre avec le sélecteur de fichiers du
 * système (aucune permission de stockage). Ni le contenu du fichier ni les données ne sont écrits dans les journaux.
 */
class BackupActivity : ComponentActivity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    private val scope = MainScope()
    private lateinit var exportButton: Button
    private lateinit var restoreButton: Button

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export(uri)
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readThenConfirm(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.backup_heading), true)
        exportButton = findViewById(R.id.backup_export_button)
        restoreButton = findViewById(R.id.backup_restore_button)
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

        exportButton.setOnClickListener { confirmExport() }
        restoreButton.setOnClickListener { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun setBusy(busy: Boolean) {
        exportButton.isEnabled = !busy
        restoreButton.isEnabled = !busy
    }

    /** Le fichier n'est pas chiffré : l'utilisateur doit le savoir avant de choisir où l'enregistrer. */
    private fun confirmExport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_export_warning_title)
            .setMessage(R.string.backup_export_warning_message)
            .setPositiveButton(R.string.backup_export_warning_continue) { _, _ ->
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                createDocument.launch("taipo-sauvegarde-$day.json")
            }
            .setNegativeButton(R.string.backup_cancel, null)
            .show()
    }

    private fun export(uri: Uri) {
        setBusy(true)
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val collected = BackupService.collect(this@BackupActivity)
                    val bytes = BackupCodec.encode(collected).toByteArray(Charsets.UTF_8)
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("Flux de sortie indisponible")
                    output.use { it.write(bytes) }
                    collected
                }
                AlertDialog.Builder(this@BackupActivity)
                    .setTitle(R.string.backup_export_done_title)
                    .setMessage(
                        getString(
                            R.string.backup_export_done_message,
                            resources.getQuantityString(R.plurals.backup_words, data.dictionary.size, data.dictionary.size),
                            resources.getQuantityString(R.plurals.backup_pins, data.pins.size, data.pins.size),
                        ),
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "export de la sauvegarde en échec", e)
                Toast.makeText(this@BackupActivity, R.string.backup_export_failed, Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    /** Lit et vérifie le fichier choisi, puis demande confirmation avant de fusionner. */
    private fun readThenConfirm(uri: Uri) {
        setBusy(true)
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { BackupCodec.decode(readLimited(uri)) }
                confirmRestore(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupCodec.InvalidBackupException) {
                val message = if (e.reason == BackupCodec.Reason.TOO_NEW) R.string.backup_error_too_new else R.string.backup_error_not_a_backup
                Toast.makeText(this@BackupActivity, message, Toast.LENGTH_LONG).show()
            } catch (e: FileTooLargeException) {
                Toast.makeText(this@BackupActivity, R.string.backup_error_too_large, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AppLog.e(TAG, "lecture de la sauvegarde en échec", e)
                Toast.makeText(this@BackupActivity, R.string.backup_error_read, Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun confirmRestore(data: BackupData) {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_restore_confirm_title)
            .setMessage(
                getString(
                    R.string.backup_restore_confirm_message,
                    resources.getQuantityString(R.plurals.backup_words, data.dictionary.size, data.dictionary.size),
                    resources.getQuantityString(R.plurals.backup_pins, data.pins.size, data.pins.size),
                ),
            )
            .setPositiveButton(R.string.backup_restore_confirm_button) { _, _ -> restore(data) }
            .setNegativeButton(R.string.backup_cancel, null)
            .show()
    }

    private fun restore(data: BackupData) {
        setBusy(true)
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) { BackupService.restore(this@BackupActivity, data) }
                AlertDialog.Builder(this@BackupActivity)
                    .setTitle(R.string.backup_restore_done_title)
                    .setMessage(restoreSummary(report))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "restauration de la sauvegarde en échec", e)
                Toast.makeText(this@BackupActivity, R.string.backup_restore_failed, Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun restoreSummary(report: BackupService.RestoreReport): String {
        val lines = mutableListOf(
            getString(
                R.string.backup_result_words,
                resources.getQuantityString(R.plurals.backup_words_added, report.wordsAdded, report.wordsAdded),
                report.wordsAlreadyThere,
            ),
            getString(
                R.string.backup_result_pins,
                resources.getQuantityString(R.plurals.backup_pins_added, report.pinsAdded, report.pinsAdded),
                report.pinsAlreadyThere,
            ),
            getString(R.string.backup_result_settings),
        )
        if (report.wordsRefused > 0 || report.pinsRefused > 0) {
            lines += getString(R.string.backup_result_refused, report.wordsRefused, report.pinsRefused)
        }
        if (report.modelNotInstalled) lines += getString(R.string.backup_result_model_not_installed)
        lines += getString(R.string.backup_result_models_reminder)
        return lines.joinToString("\n\n")
    }

    /** Lit le fichier en refusant tout ce qui dépasse [BackupCodec.MAX_BYTES] (un fichier choisi par erreur ne doit pas saturer la mémoire). */
    private fun readLimited(uri: Uri): String {
        val input = contentResolver.openInputStream(uri) ?: error("Flux d'entrée indisponible")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > BackupCodec.MAX_BYTES) throw FileTooLargeException()
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private class FileTooLargeException : Exception()

    private companion object {
        private const val TAG = "BackupActivity"
        private const val BUFFER_BYTES = 8192
    }
}
