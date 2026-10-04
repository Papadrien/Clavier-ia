package fr.junade.taipo

import android.content.Context
import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import fr.junade.taipo.dictionary.PersonalDictionary
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.dictionary.PersonalDictionaryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Écran de gestion du dictionnaire personnel (story 1.4) : ajout manuel d'un
 * mot et suppression des mots existants. Les modifications sont écrites
 * immédiatement dans la base chiffrée et prises en compte par le clavier à la
 * prochaine fin de mot tapée. La liste affichée suit le flux du dépôt.
 */
class PersonalDictionaryActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    private lateinit var repository: PersonalDictionaryRepository
    private val scope = MainScope()
    private lateinit var input: EditText
    private lateinit var list: LinearLayout
    private lateinit var countView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_personal_dictionary)
        findViewById<android.view.View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        repository = PersonalDictionaryProvider.repository(this)

        input = findViewById(R.id.personal_dictionary_input)
        list = findViewById(R.id.personal_dictionary_list)
        countView = findViewById(R.id.personal_dictionary_count)

        findViewById<Button>(R.id.personal_dictionary_add).setOnClickListener { addWordFromInput() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addWordFromInput()
                true
            } else {
                false
            }
        }

        scope.launch { repository.words.collect { render(it) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun addWordFromInput() {
        val raw = input.text.toString()
        if (raw.isBlank()) return
        scope.launch {
            val result = try {
                repository.add(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.personal_dictionary_error))
                return@launch
            }
            when (result) {
                PersonalDictionary.AddResult.ADDED -> {
                    toast(getString(R.string.personal_dictionary_added, PersonalDictionary.normalize(raw)))
                    input.text.clear()
                }
                PersonalDictionary.AddResult.ALREADY_PRESENT ->
                    toast(getString(R.string.personal_dictionary_already_present))
                PersonalDictionary.AddResult.INVALID -> toast(getString(R.string.personal_dictionary_invalid))
                PersonalDictionary.AddResult.FULL ->
                    toast(getString(R.string.personal_dictionary_full, PersonalDictionary.MAX_WORDS))
            }
        }
    }

    private fun removeWord(word: String) {
        scope.launch {
            try {
                if (repository.remove(word)) {
                    toast(getString(R.string.personal_dictionary_removed, word))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(getString(R.string.personal_dictionary_error))
            }
        }
    }

    private fun render(words: List<String>) {
        countView.text = getString(R.string.personal_dictionary_count, words.size)
        list.removeAllViews()
        if (words.isEmpty()) {
            list.addView(
                TextView(this).apply {
                    text = getString(R.string.personal_dictionary_empty)
                    textSize = 15f
                    setPadding(0, dp(8), 0, dp(8))
                    applyTaipoFontToTree()
                },
            )
            return
        }
        words.forEach { list.addView(buildRow(it)) }
        list.applyTaipoFontToTree()
    }

    private fun buildRow(word: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            TextView(this).apply {
                text = word
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this).apply {
                text = getString(R.string.personal_dictionary_remove)
                setOnClickListener { removeWord(word) }
            },
        )
        return row
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
