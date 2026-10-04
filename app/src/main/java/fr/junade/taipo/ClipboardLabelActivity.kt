package fr.junade.taipo

import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import fr.junade.taipo.clipboard.ClipboardItems
import fr.junade.taipo.clipboard.ClipboardProvider
import fr.junade.taipo.clipboard.PinnedClipRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stories 2.7 et 2.8 : pop-up de saisie de l'étiquette d'un élément épinglé, ouvert par « Ajouter une
 * étiquette » ou « Modifier l'étiquette » dans le menu d'appui long du panneau. Comme pour la
 * modification du texte ([ClipboardEditActivity]), un clavier ne pouvant pas se taper lui-même, la
 * saisie se fait dans une fenêtre de dialogue.
 *
 * L'élément est identifié par [EXTRA_PINNED_ID] et relu dans la base ; le champ est prérempli avec
 * l'étiquette existante, vide sinon. L'étiquette est limitée à [ClipboardItems.MAX_LABEL_CHARS]
 * caractères, les espaces autour sont retirés, et « Enregistrer » reste grisé tant qu'elle est vide
 * (pour la retirer : « Supprimer l'étiquette » dans le menu). Annuler ou retour : rien n'est modifié.
 */
class ClipboardLabelActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    private val scope = MainScope()
    private lateinit var repository: PinnedClipRepository
    private lateinit var title: TextView
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var saveButton: Button
    private var pinnedId = NO_ID
    private var original = ""
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pinnedId = intent.getLongExtra(EXTRA_PINNED_ID, NO_ID)
        if (pinnedId == NO_ID) {
            finish()
            return
        }
        repository = ClipboardProvider.repository(this)
        buildUi()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        scope.launch {
            val clip = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                repository.pinned.first { list -> list.any { it.id == pinnedId } }
            }?.firstOrNull { it.id == pinnedId }
            if (clip == null) {
                toast(getString(R.string.clipboard_edit_not_found))
                finish()
            } else {
                show(clip.label)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun show(label: String?) {
        original = label.orEmpty()
        loaded = true
        title.text = getString(if (label == null) R.string.clipboard_label_title_add else R.string.clipboard_label_title_edit)
        input.setText(original)
        input.setSelection(original.length)
        input.requestFocus()
        updateSaveState()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20f), dp(18f), dp(20f), dp(12f))

        title = TextView(this)
        title.text = getString(R.string.clipboard_label_title_add)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        title.useTaipoFont(TaipoType.Weight.BOLD)
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.filters = arrayOf(InputFilter.LengthFilter(ClipboardItems.MAX_LABEL_CHARS))
        input.contentDescription = getString(R.string.clipboard_label_input_description)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveState()
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                if (saveButton.isEnabled) save()
                true
            } else {
                false
            }
        }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        counter = TextView(this)
        counter.gravity = Gravity.END
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        root.addView(counter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.gravity = Gravity.END
        val cancel = Button(this)
        cancel.text = getString(R.string.clipboard_edit_cancel)
        cancel.setOnClickListener { finish() }
        saveButton = Button(this)
        saveButton.text = getString(R.string.clipboard_edit_save)
        saveButton.setOnClickListener { save() }
        buttons.addView(cancel)
        buttons.addView(saveButton)
        root.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
    }

    /** « Enregistrer » n'est actif que pour une étiquette non vide, et pas avant le chargement. */
    private fun updateSaveState() {
        counter.text = getString(R.string.clipboard_label_counter, input.text.length, ClipboardItems.MAX_LABEL_CHARS)
        saveButton.isEnabled = loaded && ClipboardItems.labelRefusal(input.text.toString()) == null
    }

    private fun save() {
        val raw = input.text.toString()
        if (ClipboardItems.normalizeLabel(raw) == original) {
            finish()
            return
        }
        saveButton.isEnabled = false
        scope.launch {
            val result = try {
                repository.setLabel(pinnedId, raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.clipboard_label_error, e.message ?: e.javaClass.simpleName))
                updateSaveState()
                return@launch
            }
            when (result) {
                ClipboardItems.LabelResult.SAVED -> {
                    toast(getString(R.string.clipboard_label_done))
                    finish()
                }
                ClipboardItems.LabelResult.NOT_FOUND -> {
                    toast(getString(R.string.clipboard_edit_not_found))
                    finish()
                }
                ClipboardItems.LabelResult.EMPTY, ClipboardItems.LabelResult.TOO_LONG -> updateSaveState()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Identifiant de l'élément épinglé dont on saisit l'étiquette. */
        const val EXTRA_PINNED_ID = "pinned_id"
        private const val NO_ID = -1L
        private const val LOAD_TIMEOUT_MS = 3_000L
    }
}
