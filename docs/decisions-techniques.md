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
Fourmi (ex Ultra-léger) = Gemma 3 270M, Colibri (ex Léger) = Gemma 3 1B, Singe (ex Équilibré) = Gemma 4 E2B, Éléphant (ex Performant) = Gemma 4 E4B (noms et sous-titres dans les ressources `model_name_*` / `model_subtitle_*` ; ids internes inchangés). Deux origines :
le fichier fourni à la main (écran « Modèle IA local ») ou le téléchargement dans l'app (épopée 8, commencée avec
Gemma 4 ; WorkManager + service au premier plan, SHA-256 embarqué par modèle). Voir `AiModel.kt` et
`docs/telechargement-modeles.md`.

## Transcription vocale
- **Nemotron 3.5 ASR Streaming** via **sherpa-onnx** (`OnlineRecognizer` / `OnlineStream`), export ONNX multilingue
  français / anglais (sherpa-onnx v1.13.5 ou plus récent, PR #3732 et #3734). Langue alignée sur la langue active du clavier.
- Intégration : les `.so` natifs dans `app/src/main/jniLibs/<abi>/` et 5 sources Kotlin du binding JNI dans
  `app/src/main/java/com/k2fsa/sherpa/onnx/` (pas de coordonnée Maven simple, pas de `.aar` utilisé). Voir les README de ces dossiers.
- Les `.so` fournis ciblent **arm64-v8a** (Pixel 9) ; l'émulateur x86_64 est possible en debug (voir `app/src/debug/jniLibs/README.md`).

## Transcription IA par modèle multimodal (épopée 14)
- Enregistrement envoyé tel quel à **Gemma 4 E2B (Équilibré) ou E4B (Performant)** : `Content.AudioBytes`, WAV PCM 16 bits,
  16 kHz, mono ; moteur chargé avec `audioBackend = Backend.CPU()`. Gemma 3 (Ultra-léger, Léger) n'a pas d'entrée audio.
- Spike 14.1 validé le 11/10/2026 (Pixel 9, voir `docs/spike-14-1.md`) : **go**, E2B recommandé. **120 secondes** au
  maximum par enregistrement : cette limite remplace le découpage de la story 14.3.
- L'audio reste **en mémoire**, jamais dans un fichier : rien à supprimer après annulation, erreur ou processus tué (point
  ouvert « fichier audio temporaire » de la story 14.2 : tranché ainsi, voir le backlog).
- Annulation par second appui pendant le traitement (`cancelProcess` puis délai avant fermeture, comme la correction).

## Données et sécurité
- Room + SQLCipher pour les données de l'utilisateur ; clé aléatoire protégée par l'Android Keystore ; la permission
  Internet ne sert qu'au téléchargement des modèles (voir `docs/securite.md` et `docs/telechargement-modeles.md`). Les modèles IA ne sont pas chiffrés.

## Demande d'avis (13/10/2026)
- **Google Play In-App Review** (`com.google.android.play:review`, version dans `gradle/libs.versions.toml`) : note en étoiles et
  commentaire dans une fenêtre en bas de l'écran, sans quitter l'application (`fr.junade.taipo.review`).
- Calendrier (`ReviewPromptPolicy`) : au **3e lancement**, puis tous les **20 lancements** : 3e, 23e, 43e et **63e**, soit 4 demandes
  au plus. Après le 63e lancement, plus aucune demande **et plus aucun comptage** (le compteur reste figé à 63). Un lancement = une
  nouvelle instance de `MainActivity` (état sauvegardé nul) ; le compteur est dans `SharedPreferences` (`review_prompt`).
- Limite de l'API : Google ne dit jamais si la personne a laissé un avis ni si la fenêtre s'est affichée : le calendrier est donc
  fixe (4 demandes au plus), sans dépendre de la réponse ; Google Play décide d'afficher ou non la fenêtre (quota). Rien ne
  s'affiche hors installation Google Play (debug, APK installé à la main) : test prévu en release, correctifs ensuite si besoin.
- Confidentialité : la fenêtre est affichée par Google Play, Taipo ne reçoit ni la note ni le commentaire (texte de la section 2
  de l'écran Confidentialité mis à jour). Aucune permission ajoutée.
