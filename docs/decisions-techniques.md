# Décisions techniques (extrait)

Ce fichier reprend, dans le dépôt, les décisions techniques du 23/09/2026 auxquelles le code renvoie (commentaires
de `build.gradle.kts`, `LlmEngineHost`, `VoiceModelFile`, README des `jniLibs`). Il remplace les références à un
document `ai-keyboard.md` qui n'était pas dans le dépôt (lot 4.3 de la revue de code, 04/10/2026). Ce n'est pas
le backlog produit : seulement ce qui explique des choix visibles dans le code.

## Moteur d'inférence texte
- **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`, figé à 0.17.1 dans `gradle/libs.versions.toml`). L'API
  MediaPipe LLM Inference est en maintenance seule ; Google recommande la migration vers LiteRT-LM.
- Accélération matérielle (CPU / GPU) choisie automatiquement ; modèles quantifiés int8.
- Un seul modèle chargé à la fois ; RAM observée d'environ 4 Go pour un modèle chargé (voir `LlmEngineHost`).
  Le rechargement après que le système a tué le processus du clavier est une limitation connue, avec indicateur de chargement.

## Modèles (4 niveaux, fichiers `.litertlm`)
Ultra-léger = Gemma 3 270M, Léger = Gemma 3 1B, Équilibré = Gemma 4 E2B, Performant = Gemma 4 E4B. Dans le prototype,
l'utilisateur fournit lui-même le fichier par le sélecteur de fichiers ; le téléchargement (avec SHA-256 embarqué par
modèle) est prévu plus tard. Voir `AiModel.kt`.

## Transcription vocale
- **Nemotron 3.5 ASR Streaming** via **sherpa-onnx** (`OnlineRecognizer` / `OnlineStream`), export ONNX multilingue
  français / anglais (sherpa-onnx v1.13.5 ou plus récent, PR #3732 et #3734). Langue alignée sur la langue active du clavier.
- Intégration : les `.so` natifs dans `app/src/main/jniLibs/<abi>/` et 5 sources Kotlin du binding JNI dans
  `app/src/main/java/com/k2fsa/sherpa/onnx/` (pas de coordonnée Maven simple, pas de `.aar` utilisé). Voir les README de ces dossiers.
- Les `.so` fournis ciblent **arm64-v8a** (Pixel 9) ; l'émulateur x86_64 est possible en debug (voir `app/src/debug/jniLibs/README.md`).

## Données et sécurité
- Room + SQLCipher pour les données de l'utilisateur ; clé aléatoire protégée par l'Android Keystore ; aucune permission
  Internet (voir `docs/securite.md`). Les modèles IA ne sont pas chiffrés.
