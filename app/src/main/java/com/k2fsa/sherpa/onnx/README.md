# app/src/main/java/com/k2fsa/sherpa/onnx/

Ce dossier doit contenir les sources Kotlin du binding JNI de sherpa-onnx
(classes utilisées par `VoiceEngine.kt` : `OnlineRecognizer`,
`OnlineRecognizerConfig`, `OnlineModelConfig`, `OnlineTransducerModelConfig`,
`OnlineStream`, etc.).

Il n'y a pas de `.aar` précompilé disponible pour ce cas d'usage (voir le
README dans `app/src/main/jniLibs/`) : ces fichiers `.kt` sont la seconde
moitié de l'intégration, celle qui appelle les `.so` placés dans `jniLibs/`.

## Où prendre les fichiers

Depuis le dépôt officiel, dossier `sherpa-onnx/kotlin-api/` :
https://github.com/k2-fsa/sherpa-onnx/tree/master/sherpa-onnx/kotlin-api

Copier tous les `.kt` de ce dossier ici, tels quels (package
`com.k2fsa.sherpa.onnx` inchangé, pour correspondre aux imports déjà présents
dans `VoiceEngine.kt`).

⚠️ Vérifier que la version copiée sur GitHub correspond à la version des
`.so` placés dans `jniLibs/` (mismatch d'API sinon). Ce projet cible v1.13.5
ou plus récent (voir décision ai-keyboard.md du 23/09/2026, export
multilingue français de Nemotron 3.5 ASR Streaming).

Une fois les `.kt` copiés, aucune autre configuration n'est nécessaire :
Gradle les compile comme n'importe quelle autre source du module `app`.
