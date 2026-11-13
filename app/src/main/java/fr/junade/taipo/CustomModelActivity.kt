package fr.junade.taipo

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import fr.junade.taipo.ai.CorrectionPromptPreferences
import fr.junade.taipo.ai.GenerationPromptPreferences
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.ai.TranscriptionPromptPreferences
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelFileResolver
import fr.junade.taipo.model.ModelPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page « Modèle personnalisé » (pack premium ; toujours ouverte en build debug, pour essayer un modèle avant de l'ajouter au catalogue).
 *
 * L'utilisateur fournit un fichier `.litertlm` depuis le système de fichiers ([AiModel.CUSTOM], même mécanisme que « Modèle IA local » :
 * permission SAF persistante, copie interne au premier chargement). Il peut le tester (chargement par LiteRT-LM), en faire le modèle du
 * clavier, dire s'il accepte l'audio, et lui donner un prompt système par fonction (correction, chat, transcription IA). Un
 * avertissement rappelle la version de LiteRT-LM intégrée ([CustomModelAccess.LITERT_LM_VERSION]) et que l'utilisateur est responsable du
 * modèle qu'il utilise.
 */
class CustomModelActivity : ComponentActivity() {

    // Écrans de l'application : même thème que le clavier (page « Apparence »), voir KeyboardTheme.wrap.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    private var busy = false

    /** « Utiliser ce modèle » : le modèle personnalisé devient le modèle du clavier, ou rend la place au modèle d'avant. */
    private val activeListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        if (checked) preferences.activateCustomModel() else preferences.deactivateCustomModel()
        render()
    }

    private lateinit var preferences: ModelPreferences
    private lateinit var fileText: TextView
    private lateinit var warningText: TextView
    private lateinit var statusText: TextView
    private lateinit var pickButton: Button
    private lateinit var testButton: Button
    private lateinit var forgetButton: Button
    private lateinit var activeSwitch: CompoundButton
    private lateinit var audioSwitch: CompoundButton

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sans le pack (build de production) : la ligne de l'accueil ouvre l'écran du pack ; garde-fou si la page est ouverte autrement.
        if (!CustomModelAccess.allowed) {
            startActivity(PremiumActivity.intent(this, PremiumActivity.SOURCE_CUSTOM_MODEL))
            finish()
            return
        }
        setContentView(R.layout.activity_custom_model)
        preferences = ModelPreferences(this)

        findViewById<TextView>(R.id.custom_model_disclaimer_compatibility).text =
            getString(R.string.custom_model_disclaimer_compatibility, CustomModelAccess.LITERT_LM_VERSION)
        fileText = findViewById(R.id.custom_model_file)
        warningText = findViewById(R.id.custom_model_warning)
        statusText = findViewById(R.id.custom_model_status)
        pickButton = findViewById(R.id.button_custom_model_pick)
        testButton = findViewById(R.id.button_custom_model_test)
        forgetButton = findViewById(R.id.button_custom_model_forget)
        activeSwitch = findViewById(R.id.switch_custom_model_active)
        audioSwitch = findViewById(R.id.switch_custom_model_audio)

        // Aucun type MIME standard pour .litertlm : tout est proposé, l'extension est vérifiée ensuite (avertissement seulement).
        pickButton.setOnClickListener { filePicker.launch(arrayOf("*/*")) }
        testButton.setOnClickListener { testModel() }
        forgetButton.setOnClickListener { forgetFile() }
        audioSwitch.isChecked = preferences.acceptsAudio(AiModel.CUSTOM)
        audioSwitch.setOnCheckedChangeListener { _, checked -> preferences.setCustomModelAudio(checked) }

        addPromptSections(findViewById(R.id.container_custom_model_prompts))

        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        render()
    }

    /** Un prompt système par fonction du clavier, propre à ce modèle (le défaut est celui du code, comme pour les autres modèles). */
    private fun addPromptSections(prompts: LinearLayout) {
        addSystemPromptSection(
            prompts,
            CorrectionPromptPreferences(this).forCustomModel(),
            R.id.edit_custom_model_prompt_correction,
            R.string.system_prompt_correction_title,
            R.string.system_prompt_correction_description,
            first = true,
        )
        addSystemPromptSection(
            prompts,
            GenerationPromptPreferences(this).forCustomModel(),
            R.id.edit_custom_model_prompt_generation,
            R.string.system_prompt_generation_title,
            R.string.system_prompt_generation_description,
            first = false,
        )
        addSystemPromptSection(
            prompts,
            TranscriptionPromptPreferences(this).forCustomModel(),
            R.id.edit_custom_model_prompt_transcription,
            R.string.system_prompt_transcription_title,
            R.string.system_prompt_transcription_description,
            first = false,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Nouveau fichier : le moteur qui porte l'ancien est fermé et son ancienne copie supprimée avant de retenir le nouveau. */
    private fun onFilePicked(uri: Uri) {
        val (name, size) = queryNameAndSize(uri)
        val appContext = applicationContext
        runWork(R.string.custom_model_status_saving) {
            withContext(Dispatchers.IO) {
                LlmEngineHost.releaseModelEverywhere(AiModel.CUSTOM)
                ModelFileResolver.deleteLocalCopy(appContext, AiModel.CUSTOM)
            }
            preferences.assignUri(AiModel.CUSTOM, uri, name, size)
            showStatus(getString(R.string.custom_model_status_saved))
        }
    }

    /**
     * « Tester le modèle » : copie le fichier si besoin puis le fait charger par LiteRT-LM, comme le clavier le fera. Le moteur du clavier
     * est d'abord fermé (deux modèles chargés à la fois dépasseraient la mémoire), et le moteur de test refermé aussitôt.
     */
    private fun testModel() {
        val appContext = applicationContext
        runWork(R.string.custom_model_status_testing) {
            val error = withContext(Dispatchers.IO) {
                LlmEngineHost.closeEverywhere()
                val host = LlmEngineHost(appContext)
                try {
                    host.preload(AiModel.CUSTOM)
                    null
                } catch (e: IllegalStateException) {
                    // ModelFileException (copie impossible) et ModelLoadException (LiteRT-LM refuse le fichier) en héritent.
                    AppLog.w(TAG, "Le modèle personnalisé ne se charge pas", e)
                    e.cause?.message ?: e.message ?: e.javaClass.simpleName
                } catch (e: IOException) {
                    AppLog.w(TAG, "Le fichier du modèle personnalisé ne se lit pas", e)
                    e.message ?: e.javaClass.simpleName
                } finally {
                    withContext(NonCancellable) { host.closeLocked() }
                }
            }
            showStatus(if (error == null) getString(R.string.custom_model_test_ok) else getString(R.string.custom_model_test_failed, error))
        }
    }

    /** « Oublier ce modèle » : ferme le moteur, supprime la référence et la copie interne ; le modèle d'avant redevient actif. */
    private fun forgetFile() {
        val appContext = applicationContext
        runWork(R.string.custom_model_status_forgetting) {
            preferences.deactivateCustomModel()
            val freed = withContext(Dispatchers.IO) {
                LlmEngineHost.releaseModelEverywhere(AiModel.CUSTOM)
                ModelPreferences(appContext).clearAndDeleteCopy(AiModel.CUSTOM)
            }
            showStatus(getString(R.string.custom_model_status_forgotten, Formatter.formatFileSize(appContext, freed)))
        }
    }

    /** Une opération à la fois : boutons désactivés et message d'attente pendant [block]. */
    private fun runWork(@StringRes waitingMessage: Int, block: suspend () -> Unit) {
        if (work?.isActive == true) return
        showStatus(getString(waitingMessage))
        work = scope.launch {
            setBusy(true)
            try {
                block()
            } finally {
                setBusy(false)
                render()
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(pickButton, testButton, forgetButton, activeSwitch).forEach { it.isEnabled = !value }
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    private fun render() {
        val name = preferences.savedFileNameFor(AiModel.CUSTOM)
        val hasFile = preferences.savedUriFor(AiModel.CUSTOM) != null
        fileText.text = if (hasFile) {
            val size = preferences.savedFileSizeFor(AiModel.CUSTOM)
            val shown = name ?: getString(R.string.custom_model_file_unnamed)
            if (size > 0) getString(R.string.custom_model_file_value, shown, Formatter.formatFileSize(this, size)) else shown
        } else {
            getString(R.string.custom_model_file_none)
        }
        // Le format attendu est .litertlm : un autre fichier est accepté (essai), mais signalé.
        val wrongExtension = hasFile && name != null && !name.endsWith(AiModel.EXPECTED_EXTENSION, ignoreCase = true)
        warningText.visibility = if (wrongExtension) View.VISIBLE else View.GONE
        if (wrongExtension) warningText.text = getString(R.string.custom_model_wrong_extension, AiModel.EXPECTED_EXTENSION)
        pickButton.setText(if (hasFile) R.string.custom_model_replace else R.string.custom_model_pick)
        testButton.visibility = if (hasFile) View.VISIBLE else View.GONE
        forgetButton.visibility = if (hasFile) View.VISIBLE else View.GONE
        // L'interrupteur reflète l'état sans relancer son écouteur.
        activeSwitch.setOnCheckedChangeListener(null)
        activeSwitch.isChecked = hasFile && preferences.activeModel() == AiModel.CUSTOM
        activeSwitch.isEnabled = hasFile && !busy
        activeSwitch.setOnCheckedChangeListener(activeListener)
    }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = it.getString(nameIndex)
                    if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }

    private companion object {
        const val TAG = "CustomModelActivity"
    }
}
