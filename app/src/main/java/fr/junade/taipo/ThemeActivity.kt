package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
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
 * Section « Forme des touches » : une carte par forme ([KeyShape]), par rangées de trois comme les thèmes ; un appui la choisit
 * ([KeyboardPreferences.keyShape]) et les aperçus des thèmes la reprennent. Les formes réservées au pass portent un cadenas et se
 * comportent comme les couleurs réservées : essayées sur les aperçus, bouton du pass, appliquées au retour avec le pass.
 */
class ThemeActivity : Activity() {

    private class Card(val choice: ThemeChoice, val view: View, val check: View, val preview: ThemePreviewView)

    private class Swatch(val palette: AccentPalette, val view: View, val disc: AccentSwatchView)

    private class ShapeCard(val shape: KeyShape, val view: View, val check: View, val lock: View, val preview: ThemePreviewView)

    private lateinit var preferences: KeyboardPreferences
    private val cards = ArrayList<Card>()
    private val swatches = ArrayList<Swatch>()
    private val shapeCards = ArrayList<ShapeCard>()

    /** Forme réservée touchée sans pass : montrée sur les aperçus, appliquée au retour si le pass a été pris. */
    private var previewShape: KeyShape? = null

    /** Couleur réservée touchée sans pass : montrée sur les aperçus, appliquée au retour si le pass a été pris. */
    private var previewPalette: AccentPalette? = null

    // Écrans de l'application : toujours sombres, quel que soit le thème du clavier (voir KeyboardTheme.wrap).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)
        preferences = KeyboardPreferences(this)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.theme_heading), true)

        val inflater = LayoutInflater.from(this)
        val available = ThemeChoice.entries.filter { it.isAvailable(Build.VERSION.SDK_INT) }
        addCardRows(findViewById(R.id.theme_cards), available) { choice, row ->
            inflater.inflate(R.layout.item_theme_card, row, false).also { card ->
                val preview = card.findViewById<ThemePreviewView>(R.id.theme_preview)
                preview.choice = choice
                card.findViewById<TextView>(R.id.theme_label).setText(shortLabel(choice))
                card.setOnClickListener { select(choice) }
                cards += Card(choice, card, card.findViewById(R.id.theme_check), preview)
            }
        }
        ViewCompat.setAccessibilityHeading(findViewById(R.id.theme_color_heading), true)
        buildPalettes(findViewById(R.id.theme_palettes))
        ViewCompat.setAccessibilityHeading(findViewById(R.id.theme_shape_heading), true)
        addCardRows(findViewById(R.id.theme_shapes), KeyShape.entries) { shape, row ->
            inflater.inflate(R.layout.item_theme_card, row, false).also { card ->
                val preview = card.findViewById<ThemePreviewView>(R.id.theme_preview)
                preview.shape = shape
                card.findViewById<TextView>(R.id.theme_label).setText(shape.labelRes())
                card.setOnClickListener { selectShape(shape) }
                shapeCards += ShapeCard(shape, card, card.findViewById(R.id.theme_check), card.findViewById(R.id.theme_lock), preview)
            }
        }
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        render(preferences.themeChoice)
        findViewById<View>(R.id.button_theme_unlock).setOnClickListener {
            startActivity(PremiumActivity.intent(this, PremiumActivity.SOURCE_THEME))
        }
        findViewById<View>(R.id.button_shape_unlock).setOnClickListener {
            startActivity(PremiumActivity.intent(this, PremiumActivity.SOURCE_THEME))
        }
        renderPalettes(preferences.accentPalette)
        renderShapes(preferences.keyShape)
    }

    /**
     * Cartes de [items] par rangées de [CARDS_PER_ROW] de même largeur ; la dernière est complétée par des vides invisibles pour
     * garder cette largeur. [card] crée la carte d'un élément (dans la rangée donnée).
     */
    private fun <T> addCardRows(container: LinearLayout, items: List<T>, card: (T, LinearLayout) -> View) {
        val gap = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_sm)
        items.chunked(CARDS_PER_ROW).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false
            repeat(CARDS_PER_ROW) { index ->
                val item = chunk.getOrNull(index)
                val view = if (item != null) card(item, row) else View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
                (view.layoutParams as LinearLayout.LayoutParams).apply { if (index > 0) marginStart = gap }
                row.addView(view)
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (rowIndex > 0) params.topMargin = gap
            container.addView(row, params)
        }
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
        val triedShape = previewShape
        if (triedShape != null && triedShape.isAvailable(PremiumPass.isActive)) {
            previewShape = null
            preferences.keyShape = triedShape
            Analytics.setting(this, "key_shape", triedShape.storageKey)
        }
        renderShapes(preferences.keyShape)
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
        for (card in shapeCards) card.preview.accent = shown
        findViewById<View>(R.id.button_theme_unlock).visibility = if (previewPalette != null) View.VISIBLE else View.GONE
    }

    private fun selectShape(shape: KeyShape) {
        if (!shape.isAvailable(PremiumPass.isActive)) {
            previewShape = shape // réservée, sans pass : essayée sur les aperçus, avec le bouton qui ouvre l'écran du pass
            renderShapes(preferences.keyShape)
            return
        }
        previewShape = null
        if (shape != preferences.keyShape) {
            preferences.keyShape = shape
            Analytics.setting(this, "key_shape", shape.storageKey)
        }
        renderShapes(shape)
    }

    /** Carte de forme choisie (contour, coche, état radio lu par TalkBack), cadenas des formes réservées, aperçus des thèmes dans cette forme. */
    private fun renderShapes(selected: KeyShape) {
        val premium = PremiumPass.isActive
        val shown = previewShape ?: KeyShape.effective(selected, premium)
        for (card in shapeCards) {
            val isSelected = card.shape == shown
            val locked = !card.shape.isAvailable(premium)
            card.view.isSelected = isSelected
            card.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.lock.visibility = if (locked) View.VISIBLE else View.GONE
            val name = getString(card.shape.labelRes())
            card.view.contentDescription = if (locked) getString(R.string.premium_locked_item, name) else name
            ViewCompat.setAccessibilityDelegate(card.view, radioDelegate(isSelected))
        }
        for (card in cards) card.preview.shape = shown
        findViewById<View>(R.id.button_shape_unlock).visibility = if (previewShape != null) View.VISIBLE else View.GONE
    }

    private fun select(choice: ThemeChoice) {
        if (choice == preferences.themeChoice) return
        preferences.themeChoice = choice
        Analytics.setting(this, "theme_choice", choice.storageKey)
        render(choice)
    }

    /** Pose la carte choisie (contour, coche) et l'état lu par TalkBack : un choix unique parmi les cartes, comme des boutons radio. */
    private fun render(selected: ThemeChoice) {
        for (card in shapeCards) card.preview.choice = selected
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

/** Nom de la forme des touches (carte de la page « Apparence »). */
@StringRes
internal fun KeyShape.labelRes(): Int = when (this) {
    KeyShape.ROUNDED -> R.string.key_shape_rounded
    KeyShape.PILL -> R.string.key_shape_pill
    KeyShape.SQUARE -> R.string.key_shape_square
    KeyShape.FLAT -> R.string.key_shape_flat
    KeyShape.ROUND -> R.string.key_shape_round
    KeyShape.BEVEL -> R.string.key_shape_bevel
    KeyShape.MECHANICAL -> R.string.key_shape_mechanical
    KeyShape.OUTLINE -> R.string.key_shape_outline
    KeyShape.BARE -> R.string.key_shape_bare
}

/** Libellé du thème, pour le sous-titre de la ligne « Thème » de l'accueil. */
@StringRes
internal fun ThemeChoice.labelRes(): Int = when (this) {
    ThemeChoice.SYSTEM -> R.string.theme_choice_system_description
    ThemeChoice.LIGHT -> R.string.theme_choice_light
    ThemeChoice.DARK -> R.string.theme_choice_dark
    ThemeChoice.DYNAMIC -> R.string.theme_choice_dynamic
}
