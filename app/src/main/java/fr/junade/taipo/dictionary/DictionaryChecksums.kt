package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage

/**
 * Audit SEC-03 : empreintes SHA-256 des listes téléchargées (dossier [DictionaryFiles.REMOTE_DIRECTORY] du serveur). Un fichier
 * dont l'empreinte diffère est refusé : ce que l'autocorrection écrit à la place de l'utilisateur ne dépend que de fichiers
 * vérifiés, pas seulement du transport HTTPS.
 *
 * Calculées sur `dictionaries/` à la racine du projet (ce qui est envoyé sur le serveur) ; `DictionaryChecksumsTest` échoue si
 * une liste du dépôt change sans que son empreinte soit mise à jour ici.
 *
 * **Changer une liste** : les versions déjà publiées de l'application refuseraient le nouveau fichier. Il faut donc l'envoyer
 * dans un nouveau dossier du serveur (par exemple `dictionaries-2026-12-01/`, mêmes chemins dedans), changer
 * [DictionaryFiles.REMOTE_DIRECTORY], mettre à jour les empreintes ici, et garder l'ancien dossier tant que des versions
 * anciennes sont en circulation. Voir `docs/dictionnaires-distants.md`.
 */
object DictionaryChecksums {

    private val full = mapOf(
        "fr" to "5a0167376e230f4ee35c4071432dbc8c59972fe249e8d0128455a411efe4ca8b",
        "en" to "74afb269c379107d8983b4c38113a507f2004b8c1e273cc4a5c8394a66438718",
        "es" to "870526b3086975e538d21673848cb91a7ef3acb452991f93ec7a98e42fddb9d7",
        "de" to "39b3d095be5ba8d468176adf716e9de7da325b4f8fa086c2c96f63c06e370a19",
        "it" to "112cb922509a3f2100f103a1a79bb0a9b442ec133d28262d49af2a81d9c6a211",
        "pt" to "eb5a47669c46e7e627bd9bf60db3ef18322f81f3b0ecd57c57bdd54f3aac032b",
        "nl" to "4bbc4aa6bf8bc1f83e7c653b12f4cc09ab43a0c7fedc1a8e73715d3ae2b0ccc4",
    )

    private val names = mapOf(
        "fr" to "e1744e51eb5abb05d5c3e2cc4d149bf71c3e381638360e45be7896521e896047",
        "en" to "dfa1b37e855636d793afd1e8deaac3cf53c5fb1c073a83801f8ba02f35a1c144",
        "es" to "be6977b36cb86b5ced30f3aecb33779215ecba282161edb7ca3a60b8fd34ad9a",
        "de" to "ecbd1b4e21acf4bbefdf77b1dc2b9cc7caefc82580ba16a9059ca77e1f944d0f",
        "it" to "135e8fe2587f396a5540083136c9ccaa5466df2c90460746bf8b9c7f093ff8b5",
        "pt" to "6ffba8d2b02dc379f5318c45328c75aeb481af22a38815acb02f5f55b89b8a71",
        "nl" to "52e86ddf8a7016b3a49e041d528388bab44f802a07a05add355a9a4a74ca9e4f",
    )

    private val bigrams = mapOf(
        "fr" to "b865e85b6cfc55dc36e9e7199548d6e86a1d081639536617bdd4f9a2430e4c5f",
        "en" to "1df61770ef856a99502915e79afcac2b0df643be98efef29aaa4ed69d20c85d7",
        "it" to "389a2175916e8b85672e74bfb575b35d21e718076c40cc414ceef055f6c29f5a",
        "pt" to "3b7619d6382945fe71f145503a523148f3d4e9f6187f3d46fd2a302e01e16c65",
        "nl" to "f0d45391b8554feb81879b9257ee71d886a4fe5e3511ba7cd76b6e3d6ea9987e",
    )

    fun of(language: KeyboardLanguage, file: DictionaryFile): String? = when (file) {
        DictionaryFile.FULL -> full[language.tag]
        DictionaryFile.NAMES -> names[language.tag]
    }

    fun bigramsOf(language: KeyboardLanguage): String? = bigrams[language.tag]
}
