package fr.junade.taipo.model

import android.content.Context
import androidx.annotation.StringRes
import fr.junade.taipo.R

/**
 * Les 4 modèles IA prévus pour le clavier (Fourmi / Colibri / Singe / Éléphant ; ids internes ultra_leger / leger / equilibre / performant).
 *
 * Deux façons d'obtenir un modèle : le téléchargement dans l'app (épopée 8, seulement les modèles qui ont une
 * [downloadUrl] : Gemma 3 et Gemma 4) ou le fichier .litertlm fourni à la main via le sélecteur de fichiers
 * du téléphone (écran « Modèle IA local », dépôt indiqué par [huggingFaceRepo]). Dans les deux cas le fichier
 * est stocké dans `filesDir/models/<id>.litertlm`.
 *
 * Tailles et dépôts vérifiés le 24/09/2026 sur Hugging Face / ai.google.dev :
 * les valeurs exactes dépendent de la variante de quantification choisie par
 * l'utilisateur (plusieurs fichiers existent par modèle), donc [approxSizeBytesMin]/
 * [approxSizeBytesMax] sont des bornes indicatives, pas une valeur exacte attendue.
 */
enum class AiModel(
    val id: String,
    /** Nom affiché (ressource, traduit selon la langue de l'app). */
    @StringRes val displayNameRes: Int,
    /** Sous-titre descriptif affiché sous le nom (ressource, traduite selon la langue de l'app). */
    @StringRes val subtitleRes: Int,
    val technicalName: String,
    val huggingFaceRepo: String,
    val approxSizeBytesMin: Long,
    val approxSizeBytesMax: Long,
    /** Indication sur le fichier attendu (ressource, traduite selon la langue de l'app). */
    @StringRes val fileHintRes: Int,
    /**
     * Empreinte SHA-256 de référence du fichier attendu, ou null tant qu'elle n'est pas relevée (lot 3.4,
     * S3). À copier depuis la page du fichier sur Hugging Face (« Copy SHA256 ») : jamais à deviner.
     * Plusieurs variantes existent pour certains modèles (voir [fileHint]) : ne renseigner que si une
     * seule variante est supportée, sinon la comparaison signalerait à tort les autres.
     */
    val sha256: String? = null,
    /**
     * Épopée 8 (8.8) : URL de téléchargement HTTPS, ou null si le modèle n'est pas (encore) téléchargeable
     * dans l'app (aucun pour l'instant : les 4 en ont une). Gemma 4 : source provisoire Hugging Face (`litert-community`) ; Gemma 3 :
     * serveur d'Adrien (Worker Cloudflare devant R2). Avant la publication, tout passe sur son serveur (URL versionnée et immuable).
     */
    val downloadUrl: String? = null,
    /** Taille de téléchargement habituelle, pour l'affichage et le contrôle d'espace avant d'avoir le `Content-Length`. */
    val approxDownloadBytes: Long? = null,
) {
    ULTRA_LEGER(
        id = "ultra_leger",
        displayNameRes = R.string.model_name_ultra_leger,
        subtitleRes = R.string.model_subtitle_ultra_leger,
        technicalName = "Gemma 3 270M (instruct)",
        huggingFaceRepo = "litert-community/gemma-3-270m-it",
        approxSizeBytesMin = 250_000_000L,
        approxSizeBytesMax = 350_000_000L,
        fileHintRes = R.string.model_hint_ultra_leger,
        sha256 = "757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63",
        downloadUrl = "https://taipo-worker.junade-models.workers.dev/gemma3-270m-it-q8.litertlm",
        approxDownloadBytes = 300_000_000L,
    ),
    LEGER(
        id = "leger",
        displayNameRes = R.string.model_name_leger,
        subtitleRes = R.string.model_subtitle_leger,
        technicalName = "Gemma 3 1B (instruct)",
        huggingFaceRepo = "litert-community/Gemma3-1B-IT",
        approxSizeBytesMin = 500_000_000L,
        approxSizeBytesMax = 1_100_000_000L,
        fileHintRes = R.string.model_hint_leger,
        sha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
        downloadUrl = "https://taipo-worker.junade-models.workers.dev/gemma3-1b-it-int4.litertlm",
        approxDownloadBytes = 600_000_000L,
    ),
    EQUILIBRE(
        id = "equilibre",
        displayNameRes = R.string.model_name_equilibre,
        subtitleRes = R.string.model_subtitle_equilibre,
        technicalName = "Gemma 4 E2B (instruct)",
        huggingFaceRepo = "litert-community/gemma-4-E2B-it-litert-lm",
        approxSizeBytesMin = 2_300_000_000L,
        approxSizeBytesMax = 2_800_000_000L,
        fileHintRes = R.string.model_hint_equilibre,
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxDownloadBytes = 2_600_000_000L,
    ),
    PERFORMANT(
        id = "performant",
        displayNameRes = R.string.model_name_performant,
        subtitleRes = R.string.model_subtitle_performant,
        technicalName = "Gemma 4 E4B (instruct)",
        huggingFaceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
        approxSizeBytesMin = 3_300_000_000L,
        approxSizeBytesMax = 3_900_000_000L,
        fileHintRes = R.string.model_hint_performant,
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        approxDownloadBytes = 3_650_000_000L,
    );

    fun displayName(context: Context): String = context.getString(displayNameRes)

    fun subtitle(context: Context): String = context.getString(subtitleRes)

    fun fileHint(context: Context): String = context.getString(fileHintRes)

    /** Vrai si l'app sait télécharger ce modèle (URL connue). */
    val hasDownloadUrl: Boolean get() = downloadUrl != null

    /**
     * Vrai si le modèle accepte de l'audio en entrée (épopée 14). D'après la documentation Google, seuls Gemma 4 E2B et
     * E4B sont entraînés pour la reconnaissance vocale ; Gemma 3 (270M, 1B) n'a pas d'entrée audio.
     */
    val hasAudioInput: Boolean get() = this == EQUILIBRE || this == PERFORMANT

    companion object {
        /** Extension de fichier attendue pour tous les modèles (format LiteRT-LM). */
        const val EXPECTED_EXTENSION = ".litertlm"

        fun byId(id: String?): AiModel? = values().firstOrNull { it.id == id }

        /** Liste ordonnée des 4 modèles, dans l'ordre Ultra-léger → Performant. */
        fun entriesOrdered(): List<AiModel> = values().toList()
    }
}

/**
 * Message d'avertissement si la taille du fichier fourni par l'utilisateur
 * s'écarte franchement de ce qui est attendu pour ce modèle, ou `null` si la
 * taille semble plausible.
 *
 * Vérification indicative uniquement : sans empreinte de référence ([AiModel.sha256], null
 * pour l'instant), rien ne garantit que le fichier est le bon modèle - seulement que sa taille
 * est cohérente avec ce qui est attendu. L'empreinte réelle est calculée à la sélection
 * ([sha256Hex]) et affichée pour comparaison manuelle.
 */
fun AiModel.sizeWarning(actualSizeBytes: Long): UiText? {
    if (actualSizeBytes <= 0) return UiText(R.string.model_size_warning_unreadable)
    val minMb = approxSizeBytesMin / 1_000_000.0
    val maxMb = approxSizeBytesMax / 1_000_000.0
    val actualMb = actualSizeBytes / 1_000_000.0
    return when {
        actualSizeBytes < approxSizeBytesMin -> UiText(R.string.model_size_warning_small, actualMb, minMb, maxMb)
        actualSizeBytes > approxSizeBytesMax -> UiText(R.string.model_size_warning_large, actualMb, minMb, maxMb)
        else -> null
    }
}

/** Texte affichable décrit par une ressource et ses arguments, résolu au moment de l'affichage (donc dans la langue de l'app). */
class UiText(@StringRes val resId: Int, vararg val args: Any) {
    fun resolve(context: Context): String = context.getString(resId, *args)
}
