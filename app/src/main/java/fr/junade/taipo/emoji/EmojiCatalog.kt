package fr.junade.taipo.emoji

import android.content.Context
import android.graphics.Paint

/**
 * Catégories du panneau emoji (story 1.15), dans l'ordre d'affichage. [fileKey] est l'identifiant
 * de la catégorie dans `assets/emoji/emoji.txt` (null pour « Récents », qui n'y figure pas) ;
 * [tabIcon] est l'emoji affiché sur l'onglet de la catégorie.
 */
enum class EmojiCategoryId(val fileKey: String?, val tabIcon: String) {
    RECENT(null, "\uD83D\uDD53"),
    SMILEYS("smileys", "\uD83D\uDE00"),
    PEOPLE("people", "\uD83D\uDC4B"),
    ANIMALS("animals", "\uD83D\uDC3B"),
    FOOD("food", "\uD83C\uDF54"),
    ACTIVITIES("activities", "\u26BD"),
    TRAVEL("travel", "\uD83D\uDE97"),
    OBJECTS("objects", "\uD83D\uDCA1"),
    SYMBOLS("symbols", "\uD83D\uDD23"),
    FLAGS("flags", "\uD83C\uDFC1"),
    ;

    companion object {
        fun fromFileKey(key: String): EmojiCategoryId? = entries.firstOrNull { it.fileKey == key }
    }
}

/** Une catégorie du catalogue avec ses emojis, dans l'ordre d'affichage. */
data class EmojiCategory(val id: EmojiCategoryId, val emojis: List<String>)

/**
 * Catalogue des emojis proposés par le panneau (story 1.15) : pas de GIF, pas de stickers, pas
 * d'emoji ASCII (décision du projet).
 *
 * Source : `assets/emoji/emoji.txt` (voir son en-tête pour le format). [parse] est pure et testée
 * en JVM ; [load] lit l'asset et masque les emojis que la police de l'appareil ne sait pas
 * afficher (un emoji plus récent que la version d'Android s'afficherait en carré vide).
 */
object EmojiCatalog {

    private const val ASSET_PATH = "emoji/emoji.txt"
    private val whitespace = Regex("\\s+")

    /**
     * Une ligne `@id` ouvre une catégorie, les lignes suivantes contiennent ses emojis séparés par
     * des espaces. Les commentaires commencent par `# ` (dièse + espace) : une ligne qui débute
     * par la touche « # » (`#` + U+FE0F + U+20E3) est donc bien une ligne d'emojis. Les catégories
     * inconnues sont ignorées avec leurs emojis.
     */
    fun parse(text: String): List<EmojiCategory> {
        val result = mutableListOf<EmojiCategory>()
        var currentId: EmojiCategoryId? = null
        var current = mutableListOf<String>()

        fun flush() {
            currentId?.let { result += EmojiCategory(it, current.toList()) }
            current = mutableListOf()
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line == "#" || line.startsWith("# ")) continue
            if (line.startsWith("@")) {
                flush()
                currentId = EmojiCategoryId.fromFileKey(line.substring(1).trim())
            } else if (currentId != null) {
                current.addAll(line.split(whitespace).filter { it.isNotEmpty() })
            }
        }
        flush()
        return result
    }

    private var cached: List<EmojiCategory>? = null

    /** Catalogue filtré selon la police de l'appareil, lu une seule fois par processus. */
    @Synchronized
    fun load(context: Context): List<EmojiCategory> {
        cached?.let { return it }
        val text = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val paint = Paint()
        val filtered = parse(text)
            .map { category -> category.copy(emojis = category.emojis.filter { paint.hasGlyph(it) }) }
            .filter { it.emojis.isNotEmpty() }
        cached = filtered
        return filtered
    }
}
