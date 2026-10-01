package fr.junade.taipo

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import fr.junade.taipo.clipboard.ClipboardEditBridge
import fr.junade.taipo.clipboard.ClipboardItems
import fr.junade.taipo.clipboard.ClipHistoryRepository
import fr.junade.taipo.clipboard.ClipboardProvider
import fr.junade.taipo.clipboard.PinnedClipRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Story 2.6 : écran de modification d'un élément du Smart Clipboard, ouvert par « Modifier » dans
 * le menu d'appui long du panneau. Un clavier ne pouvant pas se taper lui-même, l'édition se fait
 * dans une fenêtre de dialogue où Taipo s'attache à un champ ordinaire.
 *
 * - Élément épinglé ([EXTRA_PINNED_ID]) : le texte est relu dans la base par son identifiant puis
 *   réécrit au même endroit (ordre et étiquette conservés ; un doublon est autorisé).
 * - Ligne de l'historique ([EXTRA_HISTORY_ID], story 2.9) : relue et réécrite dans la base par son
 *   identifiant ; sa date de copie, donc son expiration, est conservée.
 * - Dernière copie (pas d'identifiant) : le texte vient de [ClipboardEditBridge] et le résultat y
 *   retourne, car cette copie vit en mémoire du clavier (qui met aussi à jour sa ligne d'historique).
 *
 * Annuler ou retour : rien n'est modifié.
 */
class ClipboardEditActivity : Activity() {

    private val scope = MainScope()
    private lateinit var repository: PinnedClipRepository
    private lateinit var historyRepository: ClipHistoryRepository
    private lateinit var input: EditText
    private lateinit var counter: TextView
    private lateinit var saveButton: Button
    private var pinnedId = NO_ID
    private var historyId = NO_ID
    private var original = ""
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ClipboardProvider.repository(this)
        historyRepository = ClipboardProvider.historyRepository(this)
        pinnedId = intent.getLongExtra(EXTRA_PINNED_ID, NO_ID)
        historyId = intent.getLongExtra(EXTRA_HISTORY_ID, NO_ID)
        buildUi()
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        if (pinnedId == NO_ID && historyId != NO_ID) {
            scope.launch {
                val entry = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    historyRepository.history.first { list -> list.any { it.id == historyId } }
                }?.firstOrNull { it.id == historyId }
                if (entry == null) {
                    toast(getString(R.string.clipboard_edit_not_found))
                    finish()
                } else {
                    show(entry.text)
                }
            }
        } else if (pinnedId == NO_ID) {
            // Lue une seule fois puis effacée : le texte ne reste pas dans un champ statique.
            val text = ClipboardEditBridge.lastClipText
            ClipboardEditBridge.lastClipText = null
            if (text == null) {
                finish() // processus recréé : plus rien à modifier
                return
            }
            show(text)
        } else {
            scope.launch {
                val clip = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    repository.pinned.first { list -> list.any { it.id == pinnedId } }
                }?.firstOrNull { it.id == pinnedId }
                if (clip == null) {
                    toast(getString(R.string.clipboard_edit_not_found))
                    finish()
                } else {
                    show(clip.text)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun show(text: String) {
        original = text
        loaded = true
        input.setText(text)
        input.setSelection(text.length)
        input.requestFocus()
        updateSaveState()
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20f), dp(18f), dp(20f), dp(12f))

        val title = TextView(this)
        title.text = getString(
            when {
                pinnedId != NO_ID -> R.string.clipboard_edit_title_pinned
                historyId != NO_ID -> R.string.clipboard_edit_title_history
                else -> R.string.clipboard_edit_title_last
            },
        )
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.gravity = Gravity.TOP or Gravity.START
        input.minLines = 4
        input.maxLines = 10
        input.filters = arrayOf(InputFilter.LengthFilter(ClipboardItems.MAX_PINNED_CHARS))
        input.contentDescription = getString(R.string.clipboard_edit_input_description)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateSaveState()
        })
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        counter = TextView(this)
        counter.gravity = Gravity.END
        counter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
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
    }

    /** « Enregistrer » n'est actif que pour un texte non vide, avant le chargement il ne l'est pas. */
    private fun updateSaveState() {
        val length = input.text.length
        counter.text = getString(R.string.clipboard_edit_counter, length, ClipboardItems.MAX_PINNED_CHARS)
        saveButton.isEnabled = loaded && ClipboardItems.editRefusal(input.text.toString()) == null
    }

    private fun save() {
        val text = input.text.toString()
        if (text == original) {
            finish()
            return
        }
        if (pinnedId == NO_ID && historyId == NO_ID) {
            ClipboardEditBridge.onLastClipEdited?.invoke(text)
            toast(getString(R.string.clipboard_edit_done))
            finish()
            return
        }
        saveButton.isEnabled = false
        scope.launch {
            val result = try {
                if (pinnedId != NO_ID) repository.updateText(pinnedId, text) else historyRepository.updateText(historyId, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.clipboard_edit_error, e.message ?: e.javaClass.simpleName))
                updateSaveState()
                return@launch
            }
            when (result) {
                ClipboardItems.EditResult.SAVED -> {
                    toast(getString(R.string.clipboard_edit_done))
                    finish()
                }
                ClipboardItems.EditResult.NOT_FOUND -> {
                    toast(getString(R.string.clipboard_edit_not_found))
                    finish()
                }
                ClipboardItems.EditResult.EMPTY, ClipboardItems.EditResult.TOO_LONG -> updateSaveState()
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Identifiant de l'élément épinglé à modifier ; absent pour la dernière copie. */
        const val EXTRA_PINNED_ID = "pinned_id"

        /** Story 2.9 : identifiant de la ligne d'historique à modifier ; absent pour la dernière copie et les épinglés. */
        const val EXTRA_HISTORY_ID = "history_id"
        private const val NO_ID = -1L
        private const val LOAD_TIMEOUT_MS = 3_000L
    }
}
