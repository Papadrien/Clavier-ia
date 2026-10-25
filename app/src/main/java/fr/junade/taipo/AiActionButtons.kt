package fr.junade.taipo

import android.content.Context

/**
 * Les trois actions IA de la barre : Chat, Corriger et Transcription IA. Une vue ne peut avoir qu'un parent : la barre en
 * crée deux jeux (rangée normale, et menu ouvert de la rangée de saisie) et applique chaque état aux deux. Les icônes
 * animées sont propres à chaque jeu (un Drawable n'appartient qu'à une seule vue).
 */
internal class AiActionButtons(context: Context, style: BarStyle) {

    val generateButton = LabeledIconButton(context, style)
    val correctButton = LabeledIconButton(context, style)
    val transcriptionButton = LabeledIconButton(context, style)

    /** Icône « corriger » animée : faisceau pendant le chargement ou la correction, coche retracée à la réussite. */
    val correctIcon = CorrectIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())

    /** Trois barres : onde au repos et à l'écoute, lignes pendant le traitement. */
    val transcriptionIcon = WaveIconDrawable(
        context.dimen(R.dimen.taipo_icon_size).toInt(),
        context.getColor(R.color.taipo_purple_light),
    )

    val views: List<LabeledIconButton> get() = listOf(generateButton, correctButton, transcriptionButton)

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
    }
}
