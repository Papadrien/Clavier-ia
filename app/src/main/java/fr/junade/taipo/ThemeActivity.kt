package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import fr.junade.taipo.analytics.Analytics

/**
 * Page « Thème » (paramètres, lots T5 et T7) : cartes d'aperçu, Système (automatique), Clair, Sombre et, dès Android 12,
 * Couleur dynamique ; un appui choisit le thème du clavier, la coche et le contour violet suivent. Le choix est enregistré tout de suite ([KeyboardPreferences.themeChoice]) ;
 * le clavier le prend à la prochaine ouverture d'un champ (lot T4). Les écrans de l'application, celui-ci compris, restent sombres.
 *
 * Section « Couleur » : une pastille par couleur d'accent ([AccentPalette]), par rangées de cinq ; un appui la choisit
 * ([KeyboardPreferences.accentPalette]) et les aperçus des cartes la reprennent. Couleurs réservées au pass premium ([PremiumPass]),
 * marquées d'un cadenas : sans pass, en toucher une la montre sur les aperçus sans la poser sur le clavier ([onLockedPalette]) et
 * propose le pass ; au retour avec le pass, elle est appliquée.
 *
 * Section « Police du clavier » : une ligne par police ([KeyboardFont]), écrite dans cette police. Open Sans est gratuite, les
 * autres sont réservées au pass, avec le même principe que les couleurs (essayée sans pass, appliquée au retour avec le pass).
 * Seul le clavier change de police : les écrans de l'application gardent Open Sans.
 */
class ThemeActivity : Activity() {

    private class Card(val choice: ThemeChoice, val view: View, val check: View, val preview: ThemePreviewView)

    private class Swatch(val palette: AccentPalette, val view: View, val disc: AccentSwatchView)

    private class FontRow(val font: KeyboardFont, val view: View, val mark: ImageView)

    private lateinit var preferences: KeyboardPreferences
    private val cards = ArrayList<Card>()
    private val swatches = ArrayList<Swatch>()

    /** Couleur réservée touchée sans pass : montrée sur les aperçus, appliquée au retour si le pass a été pris. */
    private var previewPalette: AccentPalette? = null
    private val fontRows = mutableListOf<FontRow>()

    /** Police réservée touchée sans pass : montrée comme choisie, avec le bouton du pass, sans être posée sur le clavier. */
    private var previewFont: KeyboardFont? = null

    // Écrans de l'application : toujours sombres, quel que soit le thème du clavier (voir KeyboardTheme.wrap).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)
        preferences = KeyboardPreferences(this)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.theme_heading), true)

        val container = findViewById<LinearLayout>(R.id.theme_cards)
        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_sm)
        // Rangées de trois cartes de même largeur ; la dernière est complétée par des vides invisibles pour garder cette largeur.
        val available = ThemeChoice.entries.filter { it.isAvailable(Build.VERSION.SDK_INT) }
        available.chunked(CARDS_PER_ROW).forEachIndexed { rowIndex, choices ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false
            repeat(CARDS_PER_ROW) { index ->
                val choice = choices.getOrNull(index)
                val view = if (choice != null) {
                    inflater.inflate(R.layout.item_theme_card, row, false).also { card ->
                        val preview = card.findViewById<ThemePreviewView>(R.id.theme_preview)
                        preview.choice = choice
                        card.findViewById<TextView>(R.id.theme_label).setText(shortLabel(choice))
                        card.setOnClickListener { select(choice) }
                        cards += Card(choice, card, card.findViewById(R.id.theme_check), preview)
                    }
                } else {
                    View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
                }
                (view.layoutParams as LinearLayout.LayoutParams).apply { if (index > 0) marginStart = gap }
                row.addView(view)
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (rowIndex > 0) params.topMargin = gap
            container.addView(row, params)
        }
        ViewCompat.setAccessibilityHeading(findViewById(R.id.theme_color_heading), true)
        buildPalettes(findViewById(R.id.theme_palettes))
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        // Après applyTaipoFontToTree : chaque ligne garde sa propre police.
        ViewCompat.setAccessibilityHeading(findViewById(R.id.theme_font_heading), true)
        buildFonts(findViewById(R.id.theme_fonts))
        findViewById<View>(R.id.button_font_unlock).setOnClickListener {
            startActivity(PremiumActivity.intent(this, PremiumActivity.SOURCE_THEME))
        }
        applySystemBarInsets()
        render(preferences.themeChoice)
        findViewById<View>(R.id.button_theme_unlock).setOnClickListener {
            startActivity(PremiumActivity.intent(this, PremiumActivity.SOURCE_THEME))
        }
        renderPalettes(preferences.accentPalette)
        renderFonts(preferences.keyboardFont)
    }

    // Retour de l'écran du pass (ou pass forcé en debug) : la couleur essayée est appliquée, les cadenas disparaissent.
    override fun onResume() {
        super.onResume()
        val tried = previewPalette
        if (tried != null && tried.isAvailable(PremiumPass.isActive)) {
            previewPalette = null
            preferences.accentPalette = tried
            Analytics.setting(this, "accent_palette", tried.storageKey)
        }
        renderPalettes(preferences.accentPalette)
        val triedFont = previewFont
        if (triedFont != null && triedFont.isAvailable(PremiumPass.isActive)) {
            previewFont = null
            preferences.keyboardFont = triedFont
            Analytics.setting(this, "keyboard_font", triedFont.storageKey)
        }
        renderFonts(preferences.keyboardFont)
    }

    /**
     * Section « Police du clavier » : une ligne par police ([KeyboardFont]), son nom et une phrase d'exemple écrits dans cette
     * police, la coche de la police choisie ou le cadenas des polices réservées au pass premium.
     */
    private fun buildFonts(container: LinearLayout) {
        val padding = resources.getDimensionPixelSize(R.dimen.taipo_settings_card_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_xs)
        val markSize = resources.getDimensionPixelSize(R.dimen.taipo_icon_size)
        KeyboardFont.entries.forEachIndexed { index, font ->
            if (index > 0) {
                val thickness = resources.getDimensionPixelSize(R.dimen.taipo_settings_divider_thickness)
                val dividerParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, thickness)
                dividerParams.marginStart = padding
                container.addView(View(this).apply { setBackgroundColor(getColor(R.color.settings_divider)) }, dividerParams)
            }
            val name = TextView(this, null, 0, R.style.TaipoText_Lead).apply {
                text = fontName(font)
                typeface = TaipoType.typeface(this@ThemeActivity, font, TaipoType.Weight.BOLD)
            }
            val sample = TextView(this, null, 0, R.style.TaipoText_Caption).apply {
                setText(R.string.theme_font_sample)
                typeface = TaipoType.typeface(this@ThemeActivity, font, TaipoType.Weight.REGULAR)
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                val sampleParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                sampleParams.topMargin = gap
                addView(sample, sampleParams)
            }
            val mark = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = resources.getDimensionPixelSize(R.dimen.taipo_settings_row_min_height)
                setPadding(padding, padding, padding, padding)
                isClickable = true
                isFocusable = true
                setBackgroundResource(R.drawable.bg_popup_item)
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(mark, LinearLayout.LayoutParams(markSize, markSize).apply { marginStart = padding })
                setOnClickListener { selectFont(font) }
            }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            fontRows += FontRow(font, row, mark)
        }
    }

    private fun fontName(font: KeyboardFont): String = font.displayName ?: getString(checkNotNull(font.labelRes))

    private fun selectFont(font: KeyboardFont) {
        if (!font.isAvailable(PremiumPass.isActive)) {
            previewFont = font
            renderFonts(preferences.keyboardFont)
            return
        }
        previewFont = null
        if (font != preferences.keyboardFont) {
            preferences.keyboardFont = font
            Analytics.setting(this, "keyboard_font", font.storageKey)
        }
        renderFonts(font)
    }

    /** Coche de la police choisie (ou essayée), cadenas des polices réservées, état radio lu par TalkBack. */
    private fun renderFonts(selected: KeyboardFont) {
        val premium = PremiumPass.isActive
        val shown = previewFont ?: KeyboardFont.effective(selected, premium)
        for (row in fontRows) {
            val isSelected = row.font == shown
            val locked = !row.font.isAvailable(premium)
            when {
                isSelected -> row.mark.setImageResource(R.drawable.bg_theme_check)
                locked -> row.mark.setImageResource(R.drawable.ic_lock)
                else -> row.mark.setImageDrawable(null)
            }
            val name = fontName(row.font)
            row.view.contentDescription = if (locked) getString(R.string.premium_locked_item, name) else name
            ViewCompat.setAccessibilityDelegate(row.view, radioDelegate(isSelected))
        }
        findViewById<View>(R.id.button_font_unlock).visibility = if (previewFont != null) View.VISIBLE else View.GONE
    }

    /** Pastilles des couleurs d'accent, par rangées de [SWATCHES_PER_ROW] de même largeur (la dernière complétée par des vides). */
    private fun buildPalettes(container: LinearLayout) {
        val size = resources.getDimensionPixelSize(R.dimen.taipo_accent_swatch_size)
        val gap = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_xs)
        val rowGap = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_md)
        AccentPalette.entries.chunked(SWATCHES_PER_ROW).forEachIndexed { rowIndex, palettes ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            repeat(SWATCHES_PER_ROW) { index ->
                val palette = palettes.getOrNull(index)
                val item = if (palette != null) {
                    val disc = AccentSwatchView(this)
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true
                        isFocusable = true
                        setBackgroundResource(R.drawable.bg_popup_item)
                        setPadding(0, gap, 0, gap)
                        addView(disc, LinearLayout.LayoutParams(size, size))
                        addView(
                            TextView(this@ThemeActivity, null, 0, R.style.TaipoText_Small).apply {
                                setText(palette.labelRes())
                                gravity = Gravity.CENTER
                                maxLines = 2
                            },
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                                .apply { topMargin = gap },
                        )
                        contentDescription = getString(palette.labelRes())
                        setOnClickListener { selectPalette(palette) }
                        swatches += Swatch(palette, this, disc)
                    }
                } else {
                    View(this)
                }
                row.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (rowIndex > 0) params.topMargin = rowGap
            container.addView(row, params)
        }
    }

    private fun selectPalette(palette: AccentPalette) {
        if (!palette.isAvailable(PremiumPass.isActive)) {
            onLockedPalette(palette)
            return
        }
        previewPalette = null
        if (palette == preferences.accentPalette) {
            renderPalettes(palette)
            return
        }
        preferences.accentPalette = palette
        Analytics.setting(this, "accent_palette", palette.storageKey)
        renderPalettes(palette)
    }

    /** Couleur réservée au pass premium, touchée sans pass : essayée sur les aperçus, avec le bouton qui ouvre l'écran du pass. */
    private fun onLockedPalette(palette: AccentPalette) {
        previewPalette = palette
        renderPalettes(preferences.accentPalette)
    }

    /** Pastille choisie (anneau, état radio lu par TalkBack) et aperçus des cartes dans cette couleur. */
    private fun renderPalettes(selected: AccentPalette) {
        val premium = PremiumPass.isActive
        val shown = previewPalette ?: AccentPalette.effective(selected, premium)
        for (swatch in swatches) {
            val isSelected = swatch.palette == shown
            val locked = !swatch.palette.isAvailable(premium)
            swatch.disc.palette = swatch.palette
            swatch.disc.isChosen = isSelected
            swatch.disc.isLocked = locked
            val name = getString(swatch.palette.labelRes())
            swatch.view.contentDescription = if (locked) getString(R.string.premium_locked_item, name) else name
            ViewCompat.setAccessibilityDelegate(swatch.view, radioDelegate(isSelected))
        }
        for (card in cards) card.preview.accent = shown
        findViewById<View>(R.id.button_theme_unlock).visibility = if (previewPalette != null) View.VISIBLE else View.GONE
    }

    private fun select(choice: ThemeChoice) {
        if (choice == preferences.themeChoice) return
        preferences.themeChoice = choice
        Analytics.setting(this, "theme_choice", choice.storageKey)
        render(choice)
    }

    /** Pose la carte choisie (contour, coche) et l'état lu par TalkBack : un choix unique parmi les cartes, comme des boutons radio. */
    private fun render(selected: ThemeChoice) {
        for (card in cards) {
            val isSelected = card.choice == selected
            card.view.isSelected = isSelected
            card.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.view.contentDescription = getString(fullLabel(card.choice))
            ViewCompat.setAccessibilityDelegate(card.view, radioDelegate(isSelected))
        }
    }

    private fun radioDelegate(checked: Boolean) = object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = RadioButton::class.java.name
            info.isCheckable = true
            info.isChecked = checked
        }
    }

    @StringRes
    private fun shortLabel(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.SYSTEM -> R.string.theme_choice_system
        ThemeChoice.LIGHT -> R.string.theme_choice_light
        ThemeChoice.DARK -> R.string.theme_choice_dark
        ThemeChoice.DYNAMIC -> R.string.theme_choice_dynamic
    }

    /** Libellé complet lu par TalkBack (« Système, automatique » ; les deux autres n'ont pas de précision). */
    @StringRes
    private fun fullLabel(choice: ThemeChoice): Int =
        if (choice == ThemeChoice.SYSTEM) R.string.theme_choice_system_description else shortLabel(choice)

    private companion object {
        const val CARDS_PER_ROW = 3
        const val SWATCHES_PER_ROW = 5
    }
}

/** Nom de la couleur d'accent (pastille de la page « Thème »). */
@StringRes
internal fun AccentPalette.labelRes(): Int = when (this) {
    AccentPalette.VIOLET -> R.string.accent_violet
    AccentPalette.INDIGO -> R.string.accent_indigo
    AccentPalette.BLUE -> R.string.accent_blue
    AccentPalette.LAGOON -> R.string.accent_lagoon
    AccentPalette.EMERALD -> R.string.accent_emerald
    AccentPalette.AMBER -> R.string.accent_amber
    AccentPalette.RASPBERRY -> R.string.accent_raspberry
    AccentPalette.MAGENTA -> R.string.accent_magenta
    AccentPalette.GRAPHITE -> R.string.accent_graphite
}

/** Libellé du thème, pour le sous-titre de la ligne « Thème » de l'accueil. */
@StringRes
internal fun ThemeChoice.labelRes(): Int = when (this) {
    ThemeChoice.SYSTEM -> R.string.theme_choice_system_description
    ThemeChoice.LIGHT -> R.string.theme_choice_light
    ThemeChoice.DARK -> R.string.theme_choice_dark
    ThemeChoice.DYNAMIC -> R.string.theme_choice_dynamic
}
