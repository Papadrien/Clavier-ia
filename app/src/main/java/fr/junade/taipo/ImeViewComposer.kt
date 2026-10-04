package fr.junade.taipo

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Construction de la vue du clavier (`onCreateInputView`) : clavier, barre du haut, barre des emojis récents,
 * panneau emoji, et assemblage avec la zone de chat, la barre et les suggestions du mode prompt et le
 * panneau Smart Clipboard. Extrait de `TaipoIme` au lot 2.3 de la revue de code (A4), sans changement de
 * comportement : chaque écouteur de vue renvoie vers [Host], l'IME reste seul à décider quoi faire.
 *
 * Le mode prompt et le presse-papiers fournissent eux-mêmes leurs vues (`createViews`, `createPanel`) :
 * ils sont passés directement. Toutes les méthodes sont à appeler depuis le thread principal.
 */
class ImeViewComposer(
    private val context: Context,
    private val prompt: PromptModeController,
    private val clipboard: ClipboardController,
    private val host: Host,
) {

    /** Ce que les vues demandent à l'IME. */
    interface Host {
        /** Échelle de hauteur du clavier (réglage « hauteur »), relue à chaque création. */
        fun heightScale(): Float

        fun onKey(key: Key)

        fun onCursorMoved(steps: Int)

        fun onDeleteSwipeUpdate(words: Int)

        fun onDeleteSwipeRelease()

        fun onDeleteSwipeCancel()

        fun onRecentEmojiClicked(emoji: String)

        fun onCorrectClicked()

        fun onVoiceTouch(event: MotionEvent): Boolean

        fun onEmojiSuggestionClicked()

        fun onWordSuggestionClicked(suggestion: WordSuggestion)

        fun openAppHome()

        fun onEmojiSelected(emoji: String)

        fun onEmojiBackspace()

        fun onEmojiPanelClose()

        /** Les vues sont toutes créées et assemblées : l'IME peut les mettre à jour (état des touches...). */
        fun onViewsCreated()
    }

    lateinit var keyboardView: KeyboardView
        private set

    lateinit var correctionBar: CorrectionBarView
        private set

    /** Story 1.15 : panneau emoji, superposé au clavier (même taille) tant qu'il est affiché. */
    lateinit var emojiPanel: EmojiPanelView
        private set

    /** Barre des emojis récents (champs de messagerie), au-dessus de la barre du haut. */
    lateinit var recentEmojiBar: RecentEmojiBarView
        private set

    /** Vrai dès que les vues ont été créées une première fois (elles sont recréées, par exemple à la rotation). */
    var isComposed = false
        private set

    /** Crée toutes les vues et renvoie la racine à afficher. */
    fun compose(): View {
        keyboardView = KeyboardView(context)
        keyboardView.heightScale = host.heightScale()
        keyboardView.setOnKeyListener { key -> host.onKey(key) }
        keyboardView.setOnCursorMoveListener { steps -> host.onCursorMoved(steps) }
        keyboardView.setOnDeleteSwipeListener(object : KeyboardView.OnDeleteSwipeListener {
            override fun onDeleteSwipeUpdate(words: Int) = host.onDeleteSwipeUpdate(words)
            override fun onDeleteSwipeRelease() = host.onDeleteSwipeRelease()
            override fun onDeleteSwipeCancel() = host.onDeleteSwipeCancel()
        })

        prompt.createViews() // zone de chat, barre du prompt et bande de suggestions (masquées)

        recentEmojiBar = RecentEmojiBarView(context)
        recentEmojiBar.visibility = View.GONE
        recentEmojiBar.setOnEmojiClickListener { emoji -> host.onRecentEmojiClicked(emoji) }

        correctionBar = CorrectionBarView(context)
        correctionBar.setOnCorrectListener { host.onCorrectClicked() }
        correctionBar.setOnGenerateClickListener { prompt.enter() }
        correctionBar.voiceButton.setOnTouchListener { _, event -> host.onVoiceTouch(event) }
        correctionBar.setOnEmojiSuggestionClickListener { host.onEmojiSuggestionClicked() }
        // Le même bouton ouvre le panneau et, tant qu'il est ouvert (bouton coloré), le referme.
        correctionBar.setOnClipboardClickListener { clipboard.onPanelButtonClicked() }
        correctionBar.setOnClipboardCloseClickListener { clipboard.hidePanel() }
        correctionBar.setOnSettingsClickListener { host.openAppHome() }
        correctionBar.setOnPasteClickListener { clipboard.onPasteTapped() }
        correctionBar.setOnWordSuggestionClickListener { suggestion -> host.onWordSuggestionClicked(suggestion) }

        emojiPanel = EmojiPanelView(context)
        emojiPanel.visibility = View.GONE
        emojiPanel.setOnEmojiSelectedListener { emoji -> host.onEmojiSelected(emoji) }
        emojiPanel.setOnBackspaceListener { host.onEmojiBackspace() }
        emojiPanel.setOnCloseListener { host.onEmojiPanelClose() }

        val clipboardPanel = clipboard.createPanel()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Fond commun à la barre du haut et au clavier (les vues enfants sont transparentes) :
            // les animations de fond futures se dessineront dans ce seul drawable.
            background = KeyboardBackgroundDrawable()
            // Story 1.8 : la bulle d'accents des touches du haut est dessinée par le clavier
            // au-dessus de sa propre zone, par-dessus la barre d'actions.
            clipChildren = false
            // Phase 5.1-2 : la zone de chat est le premier enfant, au-dessus de la barre d'emojis
            // récents et de la barre du haut (décisions 3 et 12).
            addView(
                prompt.chatZone,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                recentEmojiBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                correctionBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // Phase 5.1-4 : la barre du mode prompt prend la place de la barre du haut (masquée en dehors du mode).
            addView(
                prompt.promptBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // Suggestions du prompt, entre la pilule de saisie et les touches (masquées en dehors du mode).
            addView(
                prompt.suggestionBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // Les panneaux (emoji, Smart Clipboard) recouvrent exactement le clavier (le clavier reste
            // mesuré, seulement masqué) : la hauteur est celle du clavier et ne change pas en
            // basculant, même si le contenu d'un panneau est plus haut (rotation et réglage compris).
            addView(
                KeyboardStackLayout(context).apply {
                    clipChildren = false
                    addView(
                        keyboardView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        emojiPanel,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    addView(
                        clipboardPanel,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // Le plafond de la zone de chat (moitié de la hauteur du clavier) se mesure sur la pile
        // clavier + panneaux, dernier enfant de la racine.
        prompt.chatZone.heightReference = root.getChildAt(root.childCount - 1)
        isComposed = true
        prompt.applyViews() // la vue est recréée (rotation...) alors que le mode prompt peut être actif
        host.onViewsCreated()
        return root
    }
}
