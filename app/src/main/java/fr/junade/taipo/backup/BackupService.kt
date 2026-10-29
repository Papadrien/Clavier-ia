package fr.junade.taipo.backup

import android.content.Context
import fr.junade.taipo.HapticIntensity
import fr.junade.taipo.KeyboardHeight
import fr.junade.taipo.KeyboardPreferences
import fr.junade.taipo.ai.CorrectionPromptPreferences
import fr.junade.taipo.ai.GenerationPromptPreferences
import fr.junade.taipo.ai.SystemPromptPreferences
import fr.junade.taipo.ai.TranscriptionPromptPreferences
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
        val prompts = promptPreferences(appContext).filterValues { it.isCustom() }.mapValues { it.value.get() }
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
        settings.numberRow?.let { keyboard.isNumberRowEnabled = it }
        settings.numberRowPasswordOnly?.let { keyboard.isNumberRowInPasswordFieldsOnly = it }
        settings.showEmojiKey?.let { keyboard.isEmojiKeyShown = it }
        settings.showCommaKey?.let { keyboard.isCommaKeyShown = it }
        settings.showPeriodKey?.let { keyboard.isPeriodKeyShown = it }
        settings.hapticIntensity?.let { key ->
            HapticIntensity.entries.firstOrNull { it.storageKey == key }?.let { keyboard.hapticIntensity = it }
        }
        settings.keyboardHeight?.let { key ->
            KeyboardHeight.entries.firstOrNull { it.storageKey == key }?.let { keyboard.keyboardHeight = it }
        }
        settings.recentEmojis?.let { keyboard.recentEmojis = it.take(RecentEmojis.MAX_SIZE) }
        settings.habitLearning?.let { keyboard.isHabitLearningEnabled = it }
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

    private fun promptPreferences(context: Context): Map<String, SystemPromptPreferences> = mapOf(
        BackupAiSettings.PROMPT_CORRECTION to CorrectionPromptPreferences(context),
        BackupAiSettings.PROMPT_GENERATION to GenerationPromptPreferences(context),
        BackupAiSettings.PROMPT_TRANSCRIPTION to TranscriptionPromptPreferences(context),
    )

    /** Plafond d'un prompt lu dans un fichier : un prompt de travail fait quelques milliers de caractères. */
    private const val MAX_PROMPT_CHARS = 20_000
}
