package fr.junade.taipo

import android.content.Context

/**
 * Les boutons IA de la barre : une action par [AiAction] (Corriger, Ton, Traduire, Chat, Transcription IA) et le bouton IA, qui
 * ouvre le panneau IA (20/11/2026). Seules les actions épinglées ([AiPins]) sont affichées d'ordinaire. Une vue ne peut avoir
 * qu'un parent : la barre en crée deux jeux (rangée normale, et menu ouvert de la rangée de saisie) et applique chaque état aux
 * deux. Les icônes animées sont propres à chaque jeu (un Drawable n'appartient qu'à une seule vue).
 */
internal class AiActionButtons(context: Context, style: BarStyle) {

    val generateButton = LabeledIconButton(context, style)
    val correctButton = LabeledIconButton(context, style)
    val transcriptionButton = LabeledIconButton(context, style)
    val toneButton = LabeledIconButton(context, style)
    val translateButton = LabeledIconButton(context, style)

    /** Bouton IA : ouvre (et referme) le panneau IA ; violet tant que le panneau est ouvert. */
    val aiButton = LabeledIconButton(context, style)

    /** Icône du bouton IA animée : les étincelles deviennent quatre cases quand le panneau s'ouvre ([AiPanelIconDrawable]). */
    val aiIcon = AiPanelIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())

    /** Icône « corriger » animée : faisceau pendant le chargement ou la correction, coche retracée à la réussite. */
    val correctIcon = CorrectIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())

    /** Trois barres : onde au repos et à l'écoute, lignes pendant le traitement. */
    val transcriptionIcon = WaveIconDrawable(
        context.dimen(R.dimen.taipo_icon_size).toInt(),
        context.themeColor(R.color.taipo_purple_light),
    )

    fun button(action: AiAction): LabeledIconButton = when (action) {
        AiAction.CORRECT -> correctButton
        AiAction.TONE -> toneButton
        AiAction.TRANSLATE -> translateButton
        AiAction.CHAT -> generateButton
        AiAction.TRANSCRIPTION -> transcriptionButton
    }

    /** Toutes les vues du jeu : les actions, puis le bouton IA. */
    val views: List<LabeledIconButton> get() = AiAction.entries.map(::button) + aiButton

    init {
        // Story 5.1 : « Chat » (mode prompt). Icône seule (bouton rond) ou pilule selon la largeur.
        style.styleRoundIconButton(generateButton, R.drawable.ic_rate_review)
        generateButton.contentDescription = context.getString(R.string.generate_button_description)
        generateButton.label = context.getString(R.string.action_pill_chat)

        // Icône seule, grise comme Vocal ; l'état (chargement, correction…) passe par la transparence et la description d'accessibilité.
        style.styleRoundIconButton(correctButton, R.drawable.ic_spellcheck)
        correctButton.setImageDrawable(correctIcon) // même tracé que ic_spellcheck ; animé pendant la correction

        // Story 14.5 : bouton « voix vers texte ». Le clic est relié par l'IME.
        style.styleRoundIconButton(transcriptionButton, R.drawable.ic_speech_to_text)
        transcriptionButton.setImageDrawable(transcriptionIcon)
        transcriptionButton.contentDescription = context.getString(R.string.ai_transcription_button_description)
        transcriptionButton.label = context.getString(R.string.action_pill_transcription)

        style.styleRoundIconButton(toneButton, R.drawable.ic_tone)
        toneButton.contentDescription = context.getString(R.string.ai_action_tone)
        toneButton.label = context.getString(R.string.ai_action_tone)

        style.styleRoundIconButton(translateButton, R.drawable.ic_translate)
        translateButton.contentDescription = context.getString(R.string.ai_action_translate)
        translateButton.label = context.getString(R.string.ai_action_translate)

        style.styleRoundIconButton(aiButton, R.drawable.ic_ai_panel)
        aiButton.setImageDrawable(aiIcon) // même tracé que ic_ai_panel ; animé à l'ouverture du panneau
        aiButton.contentDescription = context.getString(R.string.ai_panel_button_description)
        aiButton.label = context.getString(R.string.ai_panel_button_label)
    }
}
