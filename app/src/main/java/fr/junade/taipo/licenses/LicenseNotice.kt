package fr.junade.taipo.licenses

import androidx.annotation.StringRes
import fr.junade.taipo.R

/** Rubrique de l'écran « Licences » dans laquelle s'affiche une mention. */
enum class LicenseSection { MODELS, DICTIONARIES, SOFTWARE }

/**
 * Une mention de licence d'un composant tiers embarqué ou téléchargé par Taipo (écran « Licences », story 1.9 de l'audit).
 *
 * Les modèles Gemma ont leurs propres cartes (voir `ModelLicense`) ; tout le reste est décrit ici, de façon
 * déclarative : l'écran construit une carte par entrée, dans l'ordre de [LicenseNotices.all].
 *
 * Les noms propres, noms de licence et lignes de copyright restent en clair (ils ne se traduisent pas) ; l'usage dans
 * Taipo et la mention complémentaire sont des ressources, donc traduites.
 */
data class LicenseNotice(
    /** Identifiant stable (unique), utile aux tests. */
    val id: String,
    val section: LicenseSection,
    val name: String,
    /** Nom officiel de la licence, tel qu'on le cite. */
    val licenseName: String,
    /** Ligne de copyright, si elle est connue avec certitude. */
    val copyright: String? = null,
    /** À quoi sert ce composant dans Taipo. */
    @StringRes val usageRes: Int,
    /** Mention exigée ou utile par la licence (attribution, modifications apportées…). */
    @StringRes val noticeRes: Int? = null,
    val projectUrl: String,
    val licenseUrl: String,
    /**
     * Texte complet de la licence dans `assets/`, à déplier. Si le fichier n'existe pas, le bouton n'est pas affiché
     * (voir `LicensesActivity`) : on n'affiche jamais un texte de licence reconstitué de mémoire.
     */
    val fullTextAsset: String? = null,
)

/** Mentions affichées dans l'écran « Licences », hors modèles Gemma. */
object LicenseNotices {

    private const val APACHE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
    private const val APACHE_NAME = "Apache License 2.0"

    /** Texte complet de la licence OpenMDW 1.1 : à déposer dans les assets (voir docs/licences.md). */
    const val OPENMDW_ASSET = "licenses/openmdw-1.1.txt"

    /** Texte complet de la licence SIL Open Font 1.1 (copie de `licenses/OpenSans-OFL.txt`). */
    const val OFL_ASSET = "licenses/open-sans-ofl.txt"

    val all: List<LicenseNotice> = listOf(
        LicenseNotice(
            id = "nemotron",
            section = LicenseSection.MODELS,
            name = "Nemotron 3.5 ASR Streaming (NVIDIA)",
            licenseName = "OpenMDW License Agreement, version 1.1 (OpenMDW-1.1)",
            copyright = "\"Nemotron\" model family © NVIDIA CORPORATION & AFFILIATES",
            usageRes = R.string.licenses_usage_nemotron,
            noticeRes = R.string.licenses_notice_nemotron,
            projectUrl = "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b",
            licenseUrl = "https://openmdw.ai/license/1-1/",
            fullTextAsset = OPENMDW_ASSET,
        ),
        LicenseNotice(
            id = "frequencywords",
            section = LicenseSection.DICTIONARIES,
            name = "FrequencyWords (Hermit Dave)",
            licenseName = "Creative Commons Attribution-ShareAlike 4.0 (CC BY-SA 4.0)",
            usageRes = R.string.licenses_usage_frequencywords,
            noticeRes = R.string.licenses_notice_frequencywords,
            projectUrl = "https://github.com/hermitdave/FrequencyWords",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/",
        ),
        LicenseNotice(
            id = "litertlm",
            section = LicenseSection.SOFTWARE,
            name = "LiteRT-LM (Google)",
            licenseName = APACHE_NAME,
            usageRes = R.string.licenses_usage_litertlm,
            projectUrl = "https://github.com/google-ai-edge/LiteRT-LM",
            licenseUrl = APACHE_URL,
        ),
        LicenseNotice(
            id = "sherpa-onnx",
            section = LicenseSection.SOFTWARE,
            name = "sherpa-onnx (k2-fsa)",
            licenseName = APACHE_NAME,
            usageRes = R.string.licenses_usage_sherpa,
            projectUrl = "https://github.com/k2-fsa/sherpa-onnx",
            licenseUrl = APACHE_URL,
        ),
        LicenseNotice(
            id = "onnxruntime",
            section = LicenseSection.SOFTWARE,
            name = "ONNX Runtime (Microsoft)",
            licenseName = "MIT License",
            copyright = "© Microsoft Corporation",
            usageRes = R.string.licenses_usage_onnxruntime,
            projectUrl = "https://github.com/microsoft/onnxruntime",
            licenseUrl = "https://opensource.org/license/mit",
        ),
        LicenseNotice(
            id = "sqlcipher",
            section = LicenseSection.SOFTWARE,
            name = "SQLCipher for Android (Zetetic)",
            licenseName = "SQLCipher Community Edition license (BSD-style)",
            copyright = "© Zetetic LLC",
            usageRes = R.string.licenses_usage_sqlcipher,
            projectUrl = "https://www.zetetic.net/sqlcipher/",
            licenseUrl = "https://www.zetetic.net/sqlcipher/license/",
        ),
        LicenseNotice(
            id = "androidx",
            section = LicenseSection.SOFTWARE,
            name = "AndroidX / Jetpack (Google)",
            licenseName = APACHE_NAME,
            usageRes = R.string.licenses_usage_androidx,
            projectUrl = "https://developer.android.com/jetpack/androidx",
            licenseUrl = APACHE_URL,
        ),
        LicenseNotice(
            id = "kotlin",
            section = LicenseSection.SOFTWARE,
            name = "Kotlin and kotlinx.coroutines (JetBrains)",
            licenseName = APACHE_NAME,
            usageRes = R.string.licenses_usage_kotlin,
            projectUrl = "https://kotlinlang.org/",
            licenseUrl = APACHE_URL,
        ),
        LicenseNotice(
            id = "aptabase",
            section = LicenseSection.SOFTWARE,
            name = "Aptabase SDK for Kotlin",
            licenseName = "MIT License",
            usageRes = R.string.licenses_usage_aptabase,
            projectUrl = "https://github.com/aptabase/aptabase-kotlin",
            licenseUrl = "https://opensource.org/license/mit",
        ),
        LicenseNotice(
            id = "opensans",
            section = LicenseSection.SOFTWARE,
            name = "Open Sans",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright 2020 The Open Sans Project Authors (https://github.com/googlefonts/opensans)",
            usageRes = R.string.licenses_usage_opensans,
            projectUrl = "https://github.com/googlefonts/opensans",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = OFL_ASSET,
        ),
    )

    fun inSection(section: LicenseSection): List<LicenseNotice> = all.filter { it.section == section }
}
