package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.dictionary.DictionaryLoader

/**
 * Détail d'une langue (écran « Langues » > une langue, piste C) : disposition des lettres à choisir pour cette langue
 * (elle s'applique quand la langue est active), état du dictionnaire (téléchargement automatique, Réessayer) et retrait de
 * la langue (bouton affiché seulement s'il en reste une autre). La langue est passée dans [EXTRA_LANGUAGE] (son code).
 */
class LanguageDetailActivity : Activity() {

    private lateinit var language: KeyboardLanguage
    private lateinit var preferences: KeyboardPreferences
    private lateinit var optionsContainer: LinearLayout
    private var dictionary: DictionaryStatusSection? = null

    /** Ligne de chaque disposition, pour mettre à jour la ligne choisie sans tout reconstruire. */
    private val optionRows = LinkedHashMap<LetterArrangement, View>()

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = KeyboardLanguage.fromTag(intent.getStringExtra(EXTRA_LANGUAGE))
        if (requested == null) {
            finish()
            return
        }
        language = requested
        preferences = KeyboardPreferences(this)
        setContentView(R.layout.activity_language_detail)
        findViewById<TextView>(R.id.language_detail_heading).apply {
            text = language.displayName
            ViewCompat.setAccessibilityHeading(this, true)
        }
        findViewById<TextView>(R.id.language_detail_footer).text = getString(R.string.language_detail_footer, language.displayName)
        optionsContainer = findViewById(R.id.language_detail_options)
        buildOptions()
        dictionary = DictionaryStatusSection(this, findViewById(R.id.language_detail_dictionary), language)
        findViewById<Button>(R.id.button_language_remove).apply {
            visibility = if (canRemove()) View.VISIBLE else View.GONE
            setOnClickListener { removeLanguage() }
        }
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
    }

    override fun onStart() {
        super.onStart()
        dictionary?.start()
    }

    override fun onStop() {
        dictionary?.stop()
        super.onStop()
    }

    private fun canRemove(): Boolean = LanguageList.canRemove(preferences.languageSetup, automaticKeyboardLanguage(), language)

    private fun buildOptions() {
        val inflater = LayoutInflater.from(this)
        LetterArrangement.entries.forEachIndexed { index, arrangement ->
            if (index > 0) optionsContainer.addView(divider())
            val row = inflater.inflate(R.layout.item_layout_option, optionsContainer, false)
            row.findViewById<TextView>(R.id.layout_option_name).text = arrangement.displayName
            row.findViewById<TextView>(R.id.layout_option_note).setText(noteOf(arrangement))
            fillPreview(row.findViewById(R.id.layout_option_preview), arrangement)
            row.setOnClickListener { choose(arrangement) }
            // Lu par TalkBack comme un bouton radio (nom + note + case cochée ou non).
            ViewCompat.setAccessibilityDelegate(
                row,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = RadioButton::class.java.name
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                },
            )
            optionsContainer.addView(row)
            optionRows[arrangement] = row
        }
        renderSelection()
    }

    private fun noteOf(arrangement: LetterArrangement): Int = when (arrangement) {
        LetterArrangement.AZERTY -> R.string.language_layout_note_azerty
        LetterArrangement.QWERTY -> R.string.language_layout_note_qwerty
        LetterArrangement.QWERTZ -> R.string.language_layout_note_qwertz
        LetterArrangement.QWERTY_N -> R.string.language_layout_note_qwerty_n
        LetterArrangement.COLEMAK -> R.string.language_layout_note_colemak
    }

    /** Aperçu de la disposition : une rangée de petits carrés par rangée de lettres (rangées centrées). */
    private fun fillPreview(preview: LinearLayout, arrangement: LetterArrangement) {
        val side = resources.getDimensionPixelSize(R.dimen.taipo_layout_preview_key)
        val gap = resources.getDimensionPixelSize(R.dimen.taipo_layout_preview_gap)
        arrangement.previewRows.forEachIndexed { rowIndex, letters ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            letters.forEachIndexed { keyIndex, _ ->
                line.addView(
                    View(this).apply {
                        setBackgroundResource(R.drawable.bg_layout_preview_key)
                        layoutParams = LinearLayout.LayoutParams(side, side).apply {
                            if (keyIndex > 0) marginStart = gap
                        }
                    },
                )
            }
            preview.addView(
                line,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    if (rowIndex > 0) topMargin = gap
                },
            )
        }
    }

    /** Ligne choisie : bouton radio plein et fond de la touche ; les autres : bouton vide, fond transparent. */
    private fun renderSelection() {
        val selected = preferences.letterArrangementOf(language)
        optionRows.forEach { (arrangement, row) ->
            val on = arrangement == selected
            row.isSelected = on
            row.setBackgroundColor(if (on) getColor(R.color.taipo_key) else Color.TRANSPARENT)
            row.findViewById<ImageView>(R.id.layout_option_radio).setImageResource(if (on) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
        }
    }

    private fun choose(arrangement: LetterArrangement) {
        if (arrangement == preferences.letterArrangementOf(language)) return
        preferences.setLetterArrangement(language, arrangement)
        // La proximité des touches du dictionnaire suit la disposition : il est relu à la prochaine ouverture d'un champ.
        DictionaryLoader.evict(language)
        Analytics.setting(this, "letter_arrangement", "${language.tag}_${arrangement.tag}")
        renderSelection()
    }

    private fun removeLanguage() {
        if (!canRemove()) return
        preferences.languageSetup = LanguageList.remove(preferences.languageSetup, automaticKeyboardLanguage(), language)
        Analytics.setting(this, "language_removed", language.tag)
        finish()
    }

    /** Trait fin entre deux lignes de la carte, aligné sur le texte. */
    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.taipo_settings_divider_thickness),
        ).apply { marginStart = resources.getDimensionPixelSize(R.dimen.taipo_settings_card_padding) }
        setBackgroundColor(getColor(R.color.settings_divider))
    }

    companion object {
        /** Code de la langue à afficher (voir [KeyboardLanguage.tag]). */
        const val EXTRA_LANGUAGE = "language"
    }
}
