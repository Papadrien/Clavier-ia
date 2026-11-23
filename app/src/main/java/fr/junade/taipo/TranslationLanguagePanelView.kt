package fr.junade.taipo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.junade.taipo.ai.TranslationLanguage
import fr.junade.taipo.ai.TranslationLanguages
import java.text.Collator

/**
 * Liste des langues du mode Traduction (maquette du 21/11/2026) : elle remplace les touches, comme le panneau IA, quand on
 * touche la langue de départ ou d'arrivée. Sections : « Détecter » (départ seulement), récentes, puis toutes les langues de
 * l'application par ordre alphabétique (dans la langue du téléphone). Une langue que le modèle actif ne traduit pas est grisée,
 * marquée du modèle qu'il faut ([unavailableTag]) ; la toucher l'explique.
 *
 * Animations : la liste monte du bas en ressort ([showAnimated]) et ses lignes apparaissent l'une après l'autre ; elle redescend
 * à la fermeture ([hideAnimated]). Animations Android coupées : affichage direct.
 */
@SuppressLint("ViewConstructor")
internal class TranslationLanguagePanelView(context: Context) : LinearLayout(context) {

    /** [language] null : « Détecter ». */
    var onLanguageChosen: ((TranslationLanguage?) -> Unit)? = null
    var onUnavailableChosen: ((TranslationLanguage) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val title = TextView(context)
    private val scroll = ScrollView(context)
    private val rows = LinearLayout(context)
    private val bottomSpacer = View(context)
    private val rowHeight = dimen(R.dimen.taipo_translation_row_height).toInt()
    private val pad = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dimen(R.dimen.taipo_key_corner_radius)
            setColor(context.themeColor(R.color.keyboard_background) and PANEL_ALPHA_MASK or PANEL_ALPHA)
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        title.setTextColor(context.themeColor(R.color.text_primary))
        title.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        title.useTaipoFont(TaipoType.Weight.MEDIUM)
        header.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val close = TextView(context).apply {
            setText(R.string.translation_picker_close)
            setTextColor(context.themeColor(R.color.accent_on_key))
            setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
            useTaipoFont(TaipoType.Weight.MEDIUM)
            gravity = Gravity.CENTER
            setPadding(pad, pad / 2, 0, pad / 2)
            announceAsButton()
            setOnClickListener { onClose?.invoke() }
        }
        header.addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rows.orientation = VERTICAL
        rows.setPadding(pad / 2, 0, pad / 2, pad / 2)
        scroll.isVerticalScrollBarEnabled = true
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottomSpacer, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    /** Marge basse (zone système), comme le clavier remplacé. */
    fun configure(bottomMarginPx: Int) {
        val params = bottomSpacer.layoutParams
        if (params.height != bottomMarginPx) {
            params.height = bottomMarginPx
            bottomSpacer.layoutParams = params
        }
    }

    /**
     * Remplit la liste. [forSource] : liste de la langue de départ (avec « Détecter »). [selected] : langue choisie (null :
     * « Détecter »). [available] : langues du modèle actif ; [recent] : récentes. [unavailableTag] : marque des autres langues.
     */
    @Suppress("LongParameterList")
    fun fill(
        forSource: Boolean,
        selected: TranslationLanguage?,
        available: List<TranslationLanguage>,
        recent: List<TranslationLanguage>,
        unavailableTag: String,
    ) {
        title.setText(if (forSource) R.string.translation_source_title else R.string.translation_target_title)
        rows.removeAllViews()
        if (forSource) {
            section(R.string.translation_section_auto)
            row(null, context.getString(R.string.translation_detect), selected == null, enabled = true, tag = null)
        }
        if (recent.isNotEmpty()) {
            section(R.string.translation_section_recent)
            recent.forEach { row(it, it.displayName(), it == selected, enabled = true, tag = null) }
        }
        section(R.string.translation_section_all)
        val collator = Collator.getInstance()
        TranslationLanguages.EXTENDED.filter { it !in recent }.sortedWith { a, b -> collator.compare(a.displayName(), b.displayName()) }
            .forEach { language ->
                val usable = language in available
                row(language, language.displayName(), language == selected, enabled = usable, tag = if (usable) null else unavailableTag)
            }
        scroll.scrollTo(0, 0)
    }

    private fun section(text: Int) {
        val label = TextView(context).apply {
            setText(text)
            isAllCaps = true
            letterSpacing = SECTION_LETTER_SPACING
            setTextColor(context.themeColor(R.color.text_hint))
            setFixedTextSizeRes(R.dimen.taipo_translation_section_text_size)
            useTaipoFont(TaipoType.Weight.MEDIUM)
            setPadding(pad, pad, pad, pad / 3)
        }
        rows.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun row(language: TranslationLanguage?, name: String, selected: Boolean, enabled: Boolean, tag: String?) {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            background = GradientDrawable().apply {
                cornerRadius = dimen(R.dimen.taipo_key_corner_radius)
                setColor(if (selected) context.themeColor(R.color.accent) and SELECTED_ALPHA_MASK or SELECTED_ALPHA else 0)
            }
            alpha = if (enabled) 1f else RESTRICTED_ALPHA
            isSelected = selected
            announceAsButton()
        }
        val label = TextView(context).apply {
            text = name
            setTextColor(context.themeColor(R.color.text_primary))
            setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
            useTaipoFont()
            maxLines = 1
        }
        line.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val end = when {
            selected -> CHECK
            tag != null -> tag
            else -> null
        }
        if (end != null) {
            line.addView(
                TextView(context).apply {
                    text = end
                    setTextColor(context.themeColor(if (selected) R.color.accent_on_key else R.color.text_hint))
                    setFixedTextSizeRes(R.dimen.taipo_translation_section_text_size)
                    useTaipoFont(TaipoType.Weight.MEDIUM)
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        line.contentDescription = if (tag != null) "$name, $tag" else name
        line.setOnClickListener {
            if (enabled || language == null) onLanguageChosen?.invoke(language) else onUnavailableChosen?.invoke(language)
        }
        rows.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
    }

    /** Monte du bas en ressort ; les lignes visibles apparaissent l'une après l'autre. */
    fun showAnimated() {
        visibility = View.VISIBLE
        if (!ValueAnimator.areAnimatorsEnabled()) {
            translationY = 0f
            alpha = 1f
            return
        }
        animate().cancel()
        translationY = (if (height > 0) height else rowHeight * ENTER_ROWS).toFloat()
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(ENTER_MS).setInterpolator(OvershootInterpolator(ENTER_OVERSHOOT)).withEndAction(null).start()
        val shift = dimen(R.dimen.taipo_translation_name_bounce)
        for (i in 0 until minOf(rows.childCount, STAGGERED_ROWS)) {
            val child = rows.getChildAt(i)
            child.alpha = 0f
            child.translationY = shift
            child.animate().alpha(1f).translationY(0f).setStartDelay(i * ROW_STAGGER_MS).setDuration(ROW_MS)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** Redescend, puis disparaît ([onHidden] ensuite). */
    fun hideAnimated(onHidden: () -> Unit) {
        if (visibility != View.VISIBLE) {
            onHidden()
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            visibility = View.GONE
            onHidden()
            return
        }
        animate().cancel()
        animate().translationY(height.toFloat() * EXIT_TRAVEL).alpha(0f).setDuration(EXIT_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
                translationY = 0f
                alpha = 1f
                onHidden()
            }.start()
    }

    private companion object {
        const val CHECK = "✓"
        const val SECTION_LETTER_SPACING = 0.08f
        const val PANEL_ALPHA_MASK = 0x00FFFFFF
        const val PANEL_ALPHA = 0xE6000000.toInt()
        const val SELECTED_ALPHA_MASK = 0x00FFFFFF
        const val SELECTED_ALPHA = 0x4D000000
        const val ENTER_MS = 420L
        const val ENTER_OVERSHOOT = 0.9f
        const val ENTER_ROWS = 6
        const val EXIT_MS = 220L
        const val EXIT_TRAVEL = 0.4f
        const val STAGGERED_ROWS = 15
        const val ROW_STAGGER_MS = 22L
        const val ROW_MS = 320L
    }
}
