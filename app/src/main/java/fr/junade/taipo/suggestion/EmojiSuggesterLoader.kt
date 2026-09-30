package fr.junade.taipo.suggestion

import android.content.Context
import android.graphics.Paint

/**
 * Charge une seule fois par processus les listes de mots-clés (`assets/emoji/suggestions-fr.txt` et
 * `suggestions-en.txt`), en écartant les emojis que la police de l'appareil ne sait pas afficher.
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
        )
        cached = suggester
        return suggester
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
