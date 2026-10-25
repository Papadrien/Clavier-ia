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
import fr.junade.taipo.dictionary.SystemUserDictionary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private lateinit var systemStatus: TextView
    private lateinit var systemAllow: Button

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

        systemStatus = findViewById(R.id.personal_dictionary_system_status)
        systemAllow = findViewById(R.id.personal_dictionary_system_allow)
        // Permission runtime : une IME ne peut pas la demander, mais cet écran est une Activity.
        systemAllow.setOnClickListener {
            requestPermissions(arrayOf(SystemUserDictionary.READ_PERMISSION), REQUEST_SYSTEM_DICTIONARY)
        }

        scope.launch { repository.words.collect { render(it) } }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemDictionary()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SYSTEM_DICTIONARY) refreshSystemDictionary()
    }

    /** Relit le dictionnaire système (hors thread principal) puis met à jour l'état affiché. */
    private fun refreshSystemDictionary() {
        val dictionary = SystemUserDictionary.get(this)
        scope.launch {
            withContext(Dispatchers.IO) { dictionary.refresh() }
            val granted = dictionary.isAccessGranted()
            systemStatus.text = if (granted) {
                getString(R.string.personal_dictionary_system_on, dictionary.count())
            } else {
                getString(R.string.personal_dictionary_system_off)
            }
            systemAllow.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
            systemStatus.applyTaipoFontToTree()
        }
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
                    setTextAppearance(R.style.TaipoText_Body)
                    val padding = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_sm)
                    setPadding(0, padding, 0, padding)
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
                setTextAppearance(R.style.TaipoText_Lead)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this, null, 0, R.style.TaipoButton_Secondary).apply {
                text = getString(R.string.personal_dictionary_remove)
                setOnClickListener { removeWord(word) }
            },
        )
        return row
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQUEST_SYSTEM_DICTIONARY = 1
    }
}
