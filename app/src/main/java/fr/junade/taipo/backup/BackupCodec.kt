package fr.junade.taipo.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Format du fichier de sauvegarde (story 10.3) : un JSON en clair (décision du 12/10/2026 : pas de chiffrement, l'utilisateur
 * est averti), encodé en UTF-8, versionné par `formatVersion`. Une version plus récente que [FORMAT_VERSION] est refusée plutôt
 * que lue de travers ; les clés inconnues d'une même version sont ignorées.
 *
 * ```
 * { "app": "taipo", "formatVersion": 1, "exportedAt": 1760000000000,
 *   "dictionary": ["Taipo", ...],
 *   "pinnedClips": [ { "text": "...", "label": "...", "pinnedAt": 1760000000000 }, ... ],
 *   "settings": { "keyboard": { "numberRow": true, ... }, "ai": { "activeModel": "...", "prompts": { "correction": "..." } } } }
 * ```
 */
object BackupCodec {
    const val APP = "taipo"
    const val FORMAT_VERSION = 1

    /** Taille maximale d'un fichier lu à la restauration : bien au-delà d'une vraie sauvegarde (50 épingles de 10 000 caractères au plus). */
    const val MAX_BYTES = 5_000_000

    enum class Reason { NOT_A_BACKUP, TOO_NEW }

    class InvalidBackupException(val reason: Reason, message: String) : Exception(message)

    fun encode(data: BackupData): String {
        val keyboard = keyboardJson(data.keyboard)
        val ai = JSONObject().apply {
            data.ai.activeModelId?.let { put("activeModel", it) }
            if (data.ai.prompts.isNotEmpty()) put("prompts", JSONObject(data.ai.prompts))
        }
        val pins = JSONArray()
        data.pins.forEach { pin ->
            pins.put(
                JSONObject().apply {
                    put("text", pin.text)
                    pin.label?.let { put("label", it) }
                    put("pinnedAt", pin.pinnedAtMillis)
                },
            )
        }
        return JSONObject().apply {
            put("app", APP)
            put("formatVersion", FORMAT_VERSION)
            put("exportedAt", data.exportedAtMillis)
            put("dictionary", JSONArray(data.dictionary))
            put("pinnedClips", pins)
            put(
                "settings",
                JSONObject().apply {
                    put("keyboard", keyboard)
                    put("ai", ai)
                },
            )
        }.toString(2)
    }

    /** Réglages du clavier : seules les valeurs connues sont écrites (un champ absent reste inchangé à la restauration). */
    private fun keyboardJson(k: BackupKeyboardSettings): JSONObject {
        val values: List<Pair<String, Any?>> = listOf(
            "numberRow" to k.numberRow,
            "numberRowPasswordOnly" to k.numberRowPasswordOnly,
            "showEmojiKey" to k.showEmojiKey,
            "showCommaKey" to k.showCommaKey,
            "showPeriodKey" to k.showPeriodKey,
            "hapticIntensity" to k.hapticIntensity,
            "keyboardHeight" to k.keyboardHeight,
            "recentEmojis" to array(k.recentEmojis),
            "habitLearning" to k.habitLearning,
            "themeChoice" to k.themeChoice,
            "accentPalette" to k.accentPalette,
            "keyShape" to k.keyShape,
            "motionStyle" to k.motionStyle,
            "keyboardFont" to k.keyboardFont,
            "backdrop" to k.backdrop,
            "backdropAnimated" to k.backdropAnimated,
            "keySound" to k.keySound,
            "barSound" to k.barSound,
            "keySoundVolume" to k.keySoundVolume,
            "bottomMarginDp" to k.bottomMarginDp,
            "adaptiveTouch" to k.adaptiveTouch,
            "clipboardHistory" to k.clipboardHistory,
            "oneHandedMode" to k.oneHandedMode,
            "oneHandedWidth" to k.oneHandedWidth,
            "floating" to k.floating,
            "floatingWidth" to k.floatingWidth,
            "keyboardLanguage" to k.keyboardLanguage,
            "multilingual" to k.multilingual,
            "multilingualLanguages" to array(k.multilingualLanguages),
            "letterArrangements" to k.letterArrangements?.let { JSONObject(it) },
        )
        return JSONObject().apply { values.forEach { (key, value) -> if (value != null) put(key, value) } }
    }

    private fun array(list: List<String>?): JSONArray? = list?.let { JSONArray(it) }

    /** Lit [text] ; lève [InvalidBackupException] si ce n'est pas une sauvegarde Taipo lisible par cette version. */
    fun decode(text: String): BackupData {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw InvalidBackupException(Reason.NOT_A_BACKUP, "JSON illisible")
        }
        if (root.optString("app") != APP) throw InvalidBackupException(Reason.NOT_A_BACKUP, "pas une sauvegarde Taipo")
        val version = root.optInt("formatVersion", -1)
        if (version < 1) throw InvalidBackupException(Reason.NOT_A_BACKUP, "version de format absente")
        if (version > FORMAT_VERSION) throw InvalidBackupException(Reason.TOO_NEW, "format $version plus récent que $FORMAT_VERSION")

        val settings = root.optJSONObject("settings")
        val keyboard = settings?.optJSONObject("keyboard")
        val ai = settings?.optJSONObject("ai")
        return BackupData(
            exportedAtMillis = root.optLong("exportedAt", 0L),
            dictionary = strings(root.optJSONArray("dictionary")),
            pins = pins(root.optJSONArray("pinnedClips")),
            keyboard = BackupKeyboardSettings(
                numberRow = keyboard.boolean("numberRow"),
                numberRowPasswordOnly = keyboard.boolean("numberRowPasswordOnly"),
                showEmojiKey = keyboard.boolean("showEmojiKey"),
                showCommaKey = keyboard.boolean("showCommaKey"),
                showPeriodKey = keyboard.boolean("showPeriodKey"),
                hapticIntensity = keyboard.string("hapticIntensity"),
                keyboardHeight = keyboard.string("keyboardHeight"),
                recentEmojis = keyboard?.optJSONArray("recentEmojis")?.let(::strings),
                habitLearning = keyboard.boolean("habitLearning"),
                themeChoice = keyboard.string("themeChoice"),
                accentPalette = keyboard.string("accentPalette"),
                keyShape = keyboard.string("keyShape"),
                motionStyle = keyboard.string("motionStyle"),
                keyboardFont = keyboard.string("keyboardFont"),
                backdrop = keyboard.string("backdrop"),
                backdropAnimated = keyboard.boolean("backdropAnimated"),
                keySound = keyboard.boolean("keySound"),
                barSound = keyboard.boolean("barSound"),
                keySoundVolume = keyboard.int("keySoundVolume"),
                bottomMarginDp = keyboard.int("bottomMarginDp"),
                adaptiveTouch = keyboard.boolean("adaptiveTouch"),
                clipboardHistory = keyboard.boolean("clipboardHistory"),
                oneHandedMode = keyboard.string("oneHandedMode"),
                oneHandedWidth = keyboard.int("oneHandedWidth"),
                floating = keyboard.boolean("floating"),
                floatingWidth = keyboard.int("floatingWidth"),
                keyboardLanguage = keyboard.string("keyboardLanguage"),
                multilingual = keyboard.boolean("multilingual"),
                multilingualLanguages = keyboard?.optJSONArray("multilingualLanguages")?.let(::strings),
                letterArrangements = keyboard?.optJSONObject("letterArrangements")?.let(::prompts),
            ),
            ai = BackupAiSettings(
                activeModelId = ai.string("activeModel"),
                prompts = prompts(ai?.optJSONObject("prompts")),
            ),
        )
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.opt(it) as? String }
    }

    private fun pins(array: JSONArray?): List<BackupPin> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val text = item.string("text") ?: return@mapNotNull null
            BackupPin(text = text, label = item.string("label"), pinnedAtMillis = item.optLong("pinnedAt", 0L))
        }
    }

    /** Objet de chaînes (prompts, dispositions) : les valeurs qui ne sont pas des chaînes sont ignorées. */
    private fun prompts(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.keys().forEach { key -> obj.string(key)?.let { out[key] = it } }
        return out
    }

    private fun JSONObject?.string(key: String): String? = this?.opt(key) as? String

    private fun JSONObject?.boolean(key: String): Boolean? = this?.opt(key) as? Boolean

    /** Entier seulement s'il est écrit comme tel (pas de conversion d'une chaîne ou d'un décimal). */
    private fun JSONObject?.int(key: String): Int? = (this?.opt(key) as? Int)
}
