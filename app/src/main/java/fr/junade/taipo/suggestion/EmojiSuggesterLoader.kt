package fr.junade.taipo.suggestion

import android.content.Context
import android.graphics.Paint
import fr.junade.taipo.KeyboardLanguage

/**
 * Charge une seule fois par processus les listes de mots-clés (`assets/emoji/suggestions-<code>.txt` : français, anglais et
 * [OTHER_LANGUAGES]), en écartant les emojis que la police de l'appareil ne sait pas afficher.
 */
object EmojiSuggesterLoader {

    private var cached: EmojiSuggester? = null

    @Synchronized
    fun get(context: Context): EmojiSuggester {
        cached?.let { return it }
        val paint = Paint()
        fun load(asset: String): Map<String, String> =
            EmojiSuggester.parse(readAsset(context, asset)).filterValues { paint.hasGlyph(it) }
        val suggester = EmojiSuggester(
            french = load("emoji/suggestions-fr.txt"),
            english = load("emoji/suggestions-en.txt"),
            others = OTHER_LANGUAGES.associateWith { load("emoji/suggestions-${it.tag}.txt") },
        )
        cached = suggester
        return suggester
    }

    /**
     * Langues qui ont aussi leur liste (`suggestions-<code>.txt`) : les huit ajoutées le 11/11/2026 depuis le 12/11, puis
     * l'espagnol, l'allemand, l'italien, le portugais et le néerlandais le 19/11/2026. Toutes les langues du clavier en ont une.
     */
    private val OTHER_LANGUAGES = KeyboardLanguage.entries - KeyboardLanguage.FR - KeyboardLanguage.EN

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
