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
        val keyboard = JSONObject().apply {
            data.keyboard.numberRow?.let { put("numberRow", it) }
            data.keyboard.hapticIntensity?.let { put("hapticIntensity", it) }
            data.keyboard.keyboardHeight?.let { put("keyboardHeight", it) }
            data.keyboard.recentEmojis?.let { put("recentEmojis", JSONArray(it)) }
            data.keyboard.habitLearning?.let { put("habitLearning", it) }
        }
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
            put("settings", JSONObject().apply { put("keyboard", keyboard); put("ai", ai) })
        }.toString(2)
    }

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
                hapticIntensity = keyboard.string("hapticIntensity"),
                keyboardHeight = keyboard.string("keyboardHeight"),
                recentEmojis = keyboard?.optJSONArray("recentEmojis")?.let(::strings),
                habitLearning = keyboard.boolean("habitLearning"),
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

    private fun prompts(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        obj.keys().forEach { key -> obj.string(key)?.let { out[key] = it } }
        return out
    }

    private fun JSONObject?.string(key: String): String? = this?.opt(key) as? String

    private fun JSONObject?.boolean(key: String): Boolean? = this?.opt(key) as? Boolean
}
