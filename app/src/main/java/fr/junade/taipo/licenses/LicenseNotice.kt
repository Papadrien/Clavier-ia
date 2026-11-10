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
            id = "aosp-dictionaries",
            section = LicenseSection.DICTIONARIES,
            name = "AOSP keyboard dictionaries (Android Open Source Project, via HeliBoard)",
            licenseName = APACHE_NAME,
            copyright = "© The Android Open Source Project",
            usageRes = R.string.licenses_usage_aosp_dictionaries,
            noticeRes = R.string.licenses_notice_aosp_dictionaries,
            projectUrl = "https://codeberg.org/Helium314/aosp-dictionaries",
            licenseUrl = APACHE_URL,
        ),
        LicenseNotice(
            id = "tatoeba",
            section = LicenseSection.DICTIONARIES,
            name = "Tatoeba",
            licenseName = "Creative Commons Attribution 2.0 France (CC BY 2.0 FR)",
            usageRes = R.string.licenses_usage_tatoeba,
            noticeRes = R.string.licenses_notice_tatoeba,
            projectUrl = "https://tatoeba.org",
            licenseUrl = "https://creativecommons.org/licenses/by/2.0/fr/",
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
        // Polices du clavier au choix (page « Thème », pass premium) : fichiers d'origine, non modifiés, texte de licence complet.
        LicenseNotice(
            id = "font-inter",
            section = LicenseSection.SOFTWARE,
            name = "Inter",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter)",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/rsms/inter",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/inter-ofl.txt",
        ),
        LicenseNotice(
            id = "font-atkinsonhyperlegiblenext",
            section = LicenseSection.SOFTWARE,
            name = "Atkinson Hyperlegible Next",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright 2020-2024 The Atkinson Hyperlegible Next Project Authors (https://github.com/googlefonts/atkinson-hyperlegible-next)",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/googlefonts/atkinson-hyperlegible-next",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/atkinsonhyperlegiblenext-ofl.txt",
        ),
        LicenseNotice(
            id = "font-lexend",
            section = LicenseSection.SOFTWARE,
            name = "Lexend",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright 2018 The Lexend Project Authors (https://github.com/googlefonts/lexend), " +
                "with Reserved Font Name \u201cRevReading Lexend\u201d",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/googlefonts/lexend",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/lexend-ofl.txt",
        ),
        LicenseNotice(
            id = "font-nunito",
            section = LicenseSection.SOFTWARE,
            name = "Nunito",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright 2014 The Nunito Project Authors (https://github.com/googlefonts/nunito)",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/googlefonts/nunito",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/nunito-ofl.txt",
        ),
        LicenseNotice(
            id = "font-manrope",
            section = LicenseSection.SOFTWARE,
            name = "Manrope",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright 2018 The Manrope Project Authors (https://github.com/googlefonts/manrope)",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/googlefonts/manrope",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/manrope-ofl.txt",
        ),
        LicenseNotice(
            id = "font-ibmplexsans",
            section = LicenseSection.SOFTWARE,
            name = "IBM Plex Sans",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright \u00a9 2017 IBM Corp. with Reserved Font Name \"Plex\"",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/IBM/plex",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/ibmplexsans-ofl.txt",
        ),
        LicenseNotice(
            id = "font-literata",
            section = LicenseSection.SOFTWARE,
            name = "Literata",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright 2017 The Literata Project Authors (https://github.com/googlefonts/literata)",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/googlefonts/literata",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/literata-ofl.txt",
        ),
        LicenseNotice(
            id = "font-opendyslexic",
            section = LicenseSection.SOFTWARE,
            name = "OpenDyslexic",
            licenseName = "SIL Open Font License, version 1.1",
            copyright = "Copyright (c) 2019-07-29, Abbie Gonzalez (https://abbiecod.es), with Reserved Font Name OpenDyslexic",
            usageRes = R.string.licenses_usage_keyboard_font,
            projectUrl = "https://github.com/antijingoist/opendyslexic",
            licenseUrl = "https://openfontlicense.org",
            fullTextAsset = "licenses/opendyslexic-ofl.txt",
        ),
    )

    fun inSection(section: LicenseSection): List<LicenseNotice> = all.filter { it.section == section }
}
