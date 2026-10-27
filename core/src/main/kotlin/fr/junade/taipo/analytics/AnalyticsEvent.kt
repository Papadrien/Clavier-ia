package fr.junade.taipo.analytics

import fr.junade.taipo.KeyboardLanguage

/**
 * Catalogue des événements de mesure d'usage envoyés à Aptabase (via son SDK, voir `Analytics` dans `:app`), et règles qui garantissent qu'aucun texte de
 * l'utilisateur n'y entre jamais. Logique pure (JVM), testée dans `:core`.
 *
 * Règle de fond : une propriété est un **nombre**, un **booléen** ou un **mot-clé court** (minuscules, chiffres,
 * `_`, `-`, `.`), jamais du texte libre. [AnalyticsProps.of] écarte tout ce qui n'en est pas un : un mot saisi, un
 * nom de fichier ou une phrase ne peut donc pas partir, même par erreur de code.
 */
object AnalyticsEvents {
    const val MODEL_DOWNLOAD_STARTED = "model_download_started"
    const val MODEL_DOWNLOAD_DONE = "model_download_done"
    const val MODEL_DOWNLOAD_FAILED = "model_download_failed"
    const val AI_ACTION = "ai_action"
    const val AI_ACTION_FAILED = "ai_action_failed"
    const val VOICE_INPUT = "voice_input"
    const val AUTOCORRECT_UNDONE = "autocorrect_undone"
    const val CLIPBOARD_USED = "clipboard_used"
    const val UNDO_USED = "undo_used"
    const val KEYBOARD_LANGUAGE = "keyboard_language"
    const val SETTING_CHANGED = "setting_changed"
    const val FIRST_OPEN = "first_open"

    /** Types d'action IA de la propriété `type`. */
    object AiType {
        const val CORRECT = "correct"
        const val GENERATE = "generate"
        const val TRANSCRIBE = "transcribe"
        const val CHAT = "chat"
    }

    /** Disposition des lettres d'une langue : AZERTY pour le français, QWERTZ pour l'allemand, QWERTY sinon. */
    fun layoutOf(language: KeyboardLanguage): String = when (language) {
        KeyboardLanguage.FR -> "azerty"
        KeyboardLanguage.DE -> "qwertz"
        else -> "qwerty"
    }

    /** Durée d'une action en tranche (jamais la valeur exacte). */
    fun durationBucket(millis: Long): String = when {
        millis < 1_000 -> "lt_1s"
        millis < 3_000 -> "1_3s"
        millis < 10_000 -> "3_10s"
        millis < 30_000 -> "10_30s"
        else -> "gt_30s"
    }

    /** Durée d'écoute en tranche (jamais la valeur exacte). */
    fun listenBucket(seconds: Long): String = when {
        seconds < 5 -> "lt_5s"
        seconds < 15 -> "5_15s"
        seconds < 60 -> "15_60s"
        else -> "gt_60s"
    }
}

/** Propriétés d'un événement, déjà filtrées : seuls nombres, booléens et mots-clés courts passent. */
class AnalyticsProps private constructor(val values: Map<String, Any>) {

    /**
     * Valeurs au format accepté par le SDK Aptabase, qui ne prend que des `String` et des `Int` : un booléen part en
     * mot-clé `"true"` / `"false"`, un `Long` devient un `Int` (écarté s'il n'y tient pas), le reste ne change pas.
     */
    fun forSdk(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((key, value) in values) {
            when (value) {
                is Boolean -> out[key] = value.toString()
                is Int, is String -> out[key] = value
                is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) out[key] = value.toInt()
            }
        }
        return out
    }

    companion object {
        /** Longueur maximale d'un mot-clé : trop court pour contenir une phrase. */
        const val MAX_KEYWORD_LENGTH = 24
        const val MAX_KEY_LENGTH = 24
        const val MAX_PROPS = 8

        private val keywordRegex = Regex("^[a-z0-9][a-z0-9_.-]*$")

        val EMPTY = AnalyticsProps(emptyMap())

        fun isKeyword(value: String): Boolean = value.length <= MAX_KEYWORD_LENGTH && keywordRegex.matches(value)

        /**
         * Construit les propriétés à partir de [pairs]. Écartées silencieusement : valeur autre que String / Int / Long /
         * Boolean, chaîne qui n'est pas un mot-clé (texte libre, majuscules, espaces), clé invalide, au-delà de
         * [MAX_PROPS]. Mieux vaut un événement incomplet qu'un texte qui fuit.
         */
        fun of(vararg pairs: Pair<String, Any?>): AnalyticsProps {
            val out = LinkedHashMap<String, Any>()
            for ((key, value) in pairs) {
                if (out.size >= MAX_PROPS) break
                if (key.length > MAX_KEY_LENGTH || !keywordRegex.matches(key)) continue
                val accepted: Any? = when (value) {
                    is Boolean, is Int, is Long -> value
                    is String -> value.takeIf { isKeyword(it) }
                    else -> null
                }
                if (accepted != null) out[key] = accepted
            }
            return AnalyticsProps(out)
        }
    }
}
