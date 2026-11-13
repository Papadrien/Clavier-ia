package fr.junade.taipo.backup

import android.content.Context
import fr.junade.taipo.AccentPalette
import fr.junade.taipo.HapticIntensity
import fr.junade.taipo.KeyShape
import fr.junade.taipo.KeyboardFont
import fr.junade.taipo.KeyboardHeight
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.KeyboardPreferences
import fr.junade.taipo.LetterArrangement
import fr.junade.taipo.MotionStyle
import fr.junade.taipo.OneHandedMode
import fr.junade.taipo.ThemeChoice
import fr.junade.taipo.ai.CorrectionPromptPreferences
import fr.junade.taipo.ai.GenerationPromptPreferences
import fr.junade.taipo.ai.SystemPromptPreferences
import fr.junade.taipo.ai.TranscriptionPromptPreferences
import fr.junade.taipo.backdrop.KeyboardBackdrop
import fr.junade.taipo.clipboard.ClipboardItems
import fr.junade.taipo.clipboard.ClipboardProvider
import fr.junade.taipo.dictionary.PersonalDictionary
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.emoji.RecentEmojis
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelPreferences

/**
 * Export et restauration des données de l'application (story 10.3). Appeler hors du thread principal : l'ouverture des bases
 * chiffrées (Keystore + SQLCipher) peut être lente.
 *
 * **Fusion** : le dictionnaire et les éléments épinglés du fichier s'ajoutent à ceux de l'appareil (les doublons sont ignorés, les
 * plafonds de l'application respectés) ; rien n'est supprimé. Les réglages présents dans le fichier remplacent les réglages actuels.
 */
object BackupService {

    /** Ce que la restauration a fait, pour le message affiché à l'utilisateur. */
    data class RestoreReport(
        val wordsAdded: Int,
        val wordsAlreadyThere: Int,
        val wordsRefused: Int,
        val pinsAdded: Int,
        val pinsAlreadyThere: Int,
        val pinsRefused: Int,
        val settingsRestored: Boolean,
        /** Le fichier désignait un modèle IA qui n'est pas installé sur cet appareil : le choix actuel est conservé. */
        val modelNotInstalled: Boolean,
    )

    suspend fun collect(context: Context, nowMillis: Long = System.currentTimeMillis()): BackupData {
        val appContext = context.applicationContext
        val keyboard = KeyboardPreferences(appContext)
        // Prompts personnalisés enregistrés, utilisés ou non (sans pass premium, ils sont gardés mais pas utilisés).
        val prompts = promptPreferences(appContext)
            .mapNotNull { (usage, preferences) -> preferences.custom()?.let { usage to it } }
            .toMap()
        return BackupData(
            exportedAtMillis = nowMillis,
            dictionary = PersonalDictionaryProvider.repository(appContext).all(),
            // Du plus ancien au plus récent : la restauration les épingle dans cet ordre.
            pins = ClipboardProvider.repository(appContext).all()
                .sortedWith(compareBy({ it.pinnedAtMillis }, { it.id }))
                .map { BackupPin(it.text, it.label, it.pinnedAtMillis) },
            keyboard = BackupKeyboardSettings(
                numberRow = keyboard.isNumberRowEnabled,
                numberRowPasswordOnly = keyboard.isNumberRowInPasswordFieldsOnly,
                showEmojiKey = keyboard.isEmojiKeyShown,
                showCommaKey = keyboard.isCommaKeyShown,
                showPeriodKey = keyboard.isPeriodKeyShown,
                hapticIntensity = keyboard.hapticIntensity.storageKey,
                keyboardHeight = keyboard.keyboardHeight.storageKey,
                recentEmojis = keyboard.recentEmojis,
                habitLearning = keyboard.isHabitLearningEnabled,
                themeChoice = keyboard.themeChoice.storageKey,
                accentPalette = keyboard.accentPalette.storageKey,
                keyShape = keyboard.keyShape.storageKey,
                motionStyle = keyboard.motionStyle.storageKey,
                keyboardFont = keyboard.keyboardFont.storageKey,
                backdrop = keyboard.keyboardBackdrop.storageKey,
                backdropAnimated = keyboard.isBackdropAnimated,
                keySound = keyboard.isKeySoundEnabled,
                barSound = keyboard.isBarSoundEnabled,
                keySoundVolume = keyboard.keySoundVolumePercent,
                bottomMarginDp = keyboard.bottomMarginDp,
                adaptiveTouch = keyboard.isAdaptiveTouchEnabled,
                clipboardHistory = keyboard.isClipboardHistoryEnabled,
                oneHandedMode = keyboard.oneHandedMode.storageKey,
                oneHandedWidth = keyboard.oneHandedWidthPercent,
                floating = keyboard.isFloatingKeyboard,
                floatingWidth = keyboard.floatingWidthPercent,
                keyboardLanguage = keyboard.keyboardLanguage?.tag.orEmpty(),
                multilingual = keyboard.isMultilingualEnabled,
                multilingualLanguages = keyboard.multilingualSelection.map { it.tag },
                letterArrangements = KeyboardLanguage.entries
                    .filter { keyboard.letterArrangementOf(it) != LetterArrangement.defaultFor(it) }
                    .associate { it.tag to keyboard.letterArrangementOf(it).tag },
            ),
            ai = BackupAiSettings(
                activeModelId = ModelPreferences(appContext).activeModel()?.id,
                prompts = prompts,
            ),
        )
    }

    suspend fun restore(context: Context, data: BackupData): RestoreReport {
        val appContext = context.applicationContext

        var wordsAdded = 0
        var wordsKnown = 0
        var wordsRefused = 0
        val dictionary = PersonalDictionaryProvider.repository(appContext)
        data.dictionary.forEach { word ->
            when (dictionary.add(word)) {
                PersonalDictionary.AddResult.ADDED -> wordsAdded++
                PersonalDictionary.AddResult.ALREADY_PRESENT -> wordsKnown++
                PersonalDictionary.AddResult.INVALID, PersonalDictionary.AddResult.FULL -> wordsRefused++
            }
        }

        var pinsAdded = 0
        var pinsKnown = 0
        var pinsRefused = 0
        val pinned = ClipboardProvider.repository(appContext)
        data.pins.sortedBy { it.pinnedAtMillis }.forEach { pin ->
            when (pinned.pinImported(pin.text, pin.label, pin.pinnedAtMillis)) {
                ClipboardItems.PinResult.PINNED -> pinsAdded++
                ClipboardItems.PinResult.ALREADY_PINNED -> pinsKnown++
                else -> pinsRefused++
            }
        }

        restoreKeyboard(appContext, data.keyboard)
        val modelNotInstalled = restoreAi(appContext, data.ai)

        return RestoreReport(
            wordsAdded = wordsAdded,
            wordsAlreadyThere = wordsKnown,
            wordsRefused = wordsRefused,
            pinsAdded = pinsAdded,
            pinsAlreadyThere = pinsKnown,
            pinsRefused = pinsRefused,
            settingsRestored = true,
            modelNotInstalled = modelNotInstalled,
        )
    }

    /** Applique les réglages du clavier présents dans le fichier ; une valeur inconnue de cette version est ignorée. */
    private fun restoreKeyboard(context: Context, settings: BackupKeyboardSettings) {
        val keyboard = KeyboardPreferences(context)
        restoreKeys(keyboard, settings)
        restoreLook(keyboard, settings)
        restoreBehaviour(keyboard, settings)
        restoreLanguages(keyboard, settings)
    }

    /** Touches affichées, retour haptique, hauteur, emojis récents, apprentissage. */
    private fun restoreKeys(keyboard: KeyboardPreferences, settings: BackupKeyboardSettings) {
        settings.numberRow?.let { keyboard.isNumberRowEnabled = it }
        settings.numberRowPasswordOnly?.let { keyboard.isNumberRowInPasswordFieldsOnly = it }
        settings.showEmojiKey?.let { keyboard.isEmojiKeyShown = it }
        settings.showCommaKey?.let { keyboard.isCommaKeyShown = it }
        settings.showPeriodKey?.let { keyboard.isPeriodKeyShown = it }
        settings.hapticIntensity?.let { key -> HapticIntensity.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.hapticIntensity = it }
        settings.keyboardHeight?.let { key -> KeyboardHeight.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.keyboardHeight = it }
        // Audit UX-06 : seuls des emojis courts sont repris (un fichier modifié à la main ne peut pas injecter de long texte).
        settings.recentEmojis?.let { list ->
            keyboard.recentEmojis = list.filter { it.isNotBlank() && it.length <= MAX_EMOJI_CHARS }.take(RecentEmojis.MAX_SIZE)
        }
        settings.habitLearning?.let { keyboard.isHabitLearningEnabled = it }
    }

    /**
     * Apparence. Écrire ces réglages met aussi à jour KeyboardTheme (choix, couleur, forme) ; une couleur ou une forme réservée
     * au pass premium retombe sur celle d'origine sans le pass, et « dynamic » avant Android 12 se comporte comme « system ».
     */
    private fun restoreLook(keyboard: KeyboardPreferences, settings: BackupKeyboardSettings) {
        settings.themeChoice?.let { key -> ThemeChoice.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.themeChoice = it }
        settings.accentPalette?.let { key -> AccentPalette.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.accentPalette = it }
        settings.keyShape?.let { key -> KeyShape.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.keyShape = it }
        settings.motionStyle?.let { key -> MotionStyle.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.motionStyle = it }
        settings.keyboardFont?.let { key -> KeyboardFont.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.keyboardFont = it }
        settings.backdrop?.let { key -> KeyboardBackdrop.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.keyboardBackdrop = it }
        settings.backdropAnimated?.let { keyboard.isBackdropAnimated = it }
    }

    /** Sons, marge, zones de frappe, historique, mode une main et flottant (valeurs bornées par les réglages eux-mêmes). */
    private fun restoreBehaviour(keyboard: KeyboardPreferences, settings: BackupKeyboardSettings) {
        settings.keySound?.let { keyboard.isKeySoundEnabled = it }
        settings.barSound?.let { keyboard.isBarSoundEnabled = it }
        settings.keySoundVolume?.let { keyboard.keySoundVolumePercent = it }
        settings.bottomMarginDp?.let { keyboard.bottomMarginDp = it }
        settings.adaptiveTouch?.let { keyboard.isAdaptiveTouchEnabled = it }
        settings.clipboardHistory?.let { keyboard.isClipboardHistoryEnabled = it }
        settings.oneHandedMode?.let { key -> OneHandedMode.entries.firstOrNull { it.storageKey == key } }?.let { keyboard.oneHandedMode = it }
        settings.oneHandedWidth?.let { keyboard.oneHandedWidthPercent = it }
        settings.floating?.let { keyboard.isFloatingKeyboard = it }
        settings.floatingWidth?.let { keyboard.floatingWidthPercent = it }
    }

    /** Langue choisie (vide = automatique), clavier multilingue et dispositions : codes inconnus ignorés. */
    private fun restoreLanguages(keyboard: KeyboardPreferences, settings: BackupKeyboardSettings) {
        settings.keyboardLanguage?.let { tag ->
            if (tag.isEmpty()) keyboard.keyboardLanguage = null else KeyboardLanguage.fromTag(tag)?.let { keyboard.keyboardLanguage = it }
        }
        settings.multilingualLanguages?.let { tags -> keyboard.multilingualSelection = tags.mapNotNull(KeyboardLanguage::fromTag).distinct() }
        settings.multilingual?.let { keyboard.isMultilingualEnabled = it }
        settings.letterArrangements?.forEach { (languageTag, arrangementTag) ->
            val language = KeyboardLanguage.fromTag(languageTag) ?: return@forEach
            val arrangement = LetterArrangement.fromTag(arrangementTag) ?: return@forEach
            keyboard.setLetterArrangement(language, arrangement)
        }
    }

    /**
     * Applique les prompts personnalisés et le modèle choisi. Le modèle n'est repris que s'il est installé sur cet appareil :
     * sinon le choix actuel est gardé (renvoie vrai). Sur un appareil neuf, le modèle se choisit après son téléchargement.
     */
    private fun restoreAi(context: Context, settings: BackupAiSettings): Boolean {
        val prompts = promptPreferences(context)
        settings.prompts.forEach { (usage, prompt) ->
            if (prompt.length <= MAX_PROMPT_CHARS) prompts[usage]?.save(prompt)
        }
        val modelId = settings.activeModelId ?: return false
        val model = AiModel.byId(modelId) ?: return false
        val modelPreferences = ModelPreferences(context)
        if (!modelPreferences.isInstalled(model)) return true
        modelPreferences.setActiveModel(model)
        return false
    }

    private fun promptPreferences(context: Context): Map<String, SystemPromptPreferences> {
        val base = mapOf(
            BackupAiSettings.PROMPT_CORRECTION to CorrectionPromptPreferences(context),
            BackupAiSettings.PROMPT_GENERATION to GenerationPromptPreferences(context),
            BackupAiSettings.PROMPT_TRANSCRIPTION to TranscriptionPromptPreferences(context),
        )
        // Prompts du modèle personnalisé : mêmes usages, préfixés (« custom_model_correction »...).
        return base + base.map { (usage, preferences) -> BackupAiSettings.CUSTOM_MODEL_PREFIX + usage to preferences.forCustomModel() }
    }

    /** Plafond d'un prompt lu dans un fichier : un prompt de travail fait quelques milliers de caractères. */
    private const val MAX_PROMPT_CHARS = 20_000

    /** Un emoji (séquences ZWJ et drapeaux compris) tient largement dans 16 caractères UTF-16. */
    private const val MAX_EMOJI_CHARS = 16
}
