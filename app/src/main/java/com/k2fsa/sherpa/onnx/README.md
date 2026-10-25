# app/src/main/java/com/k2fsa/sherpa/onnx/

Sources Kotlin du binding JNI de sherpa-onnx, **réduites au strict nécessaire** (lot 2.2 de la revue
de code, 03/10/2026). Elles sont la seconde moitié de l'intégration : elles appellent les `.so`
placés dans `app/src/main/jniLibs/<abi>/` (voir le README de ce dossier). Il n'y a pas de `.aar`
précompilé pour ce cas d'usage.

## Fichiers conservés (5)

| Fichier | Rôle |
|---|---|
| `OnlineRecognizer.kt` | Reconnaissance en flux (`OnlineRecognizer`, `OnlineRecognizerConfig`, `OnlineModelConfig`, `OnlineTransducerModelConfig`…), utilisée par `VoiceEngine.kt` |
| `OnlineStream.kt` | Flux audio d'entrée, utilisé par `VoiceRecorder.kt` |
| `FeatureConfig.kt`, `HomophoneReplacerConfig.kt`, `QnnConfig.kt` | Classes de configuration référencées par `OnlineRecognizerConfig` (le code natif lit leurs champs) |

Les 18 autres fichiers du dossier `kotlin-api` (reconnaissance hors ligne, synthèse vocale, VAD,
diarisation, débruitage, ponctuation, `WaveReader`, `VersionInfo`, etc.) ont été supprimés : rien
dans l'app ne les utilise.

## Ajouter un fichier plus tard

Copier le `.kt` voulu depuis le dépôt officiel, dossier `sherpa-onnx/kotlin-api/` :
https://github.com/k2-fsa/sherpa-onnx/tree/master/sherpa-onnx/kotlin-api
(package `com.k2fsa.sherpa.onnx` inchangé). Si le compilateur réclame un fichier supprimé
(ex. `VersionInfo.kt`), le recopier de la même façon.

⚠️ La version des sources doit correspondre à celle des `.so` de `jniLibs/` (sinon incompatibilité
d'API). Ce projet cible sherpa-onnx v1.13.5 ou plus récent (export multilingue français de
Nemotron 3.5 ASR Streaming).

Les règles R8 (`app/proguard-rules.pro`) conservent tout le paquet `com.k2fsa.sherpa.onnx.**` :
le code natif retrouve ces classes et leurs champs par nom.
