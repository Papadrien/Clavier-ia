# app/src/main/jniLibs/

Ce dossier doit contenir les bibliothèques natives (`.so`) de sherpa-onnx
(moteur de transcription vocale, voir `VoiceEngine.kt`), une sous-dossier par
ABI.

Gradle détecte automatiquement ce dossier (comportement par défaut du plugin
Android) : aucune configuration `sourceSets`/`jniLibs.srcDirs` supplémentaire
n'est nécessaire.

## Où prendre les fichiers

Depuis l'archive `sherpa-onnx-vX.Y.Z-android.tar.bz2` téléchargée sur
https://github.com/k2-fsa/sherpa-onnx/releases (ou son miroir Hugging Face
https://huggingface.co/csukuangfj/sherpa-onnx-libs), dans le sous-dossier
`jniLibs/arm64-v8a/` de l'archive, copier ici :

- `libsherpa-onnx-jni.so`
- `libsherpa-onnx-c-api.so`
- `libsherpa-onnx-cxx-api.so`
- `libonnxruntime.so`

Résultat attendu :

```
app/src/main/jniLibs/
  arm64-v8a/
    libsherpa-onnx-jni.so
    libsherpa-onnx-c-api.so
    libsherpa-onnx-cxx-api.so
    libonnxruntime.so
```

Le dossier `arm64-v8a` cible le Pixel 9 (voir décision ai-keyboard.md du
23/09/2026). Si un jour d'autres ABI sont nécessaires (ex. `x86_64` pour
l'émulateur), créer les sous-dossiers correspondants de la même façon.

## L'autre moitié : les sources Kotlin

Ces `.so` seuls ne suffisent pas : `VoiceEngine.kt` importe des classes
Kotlin (`com.k2fsa.sherpa.onnx.OnlineRecognizer` etc.) qui font le pont JNI
vers ces bibliothèques natives. Voir le README dans
`app/src/main/java/com/k2fsa/sherpa/onnx/` pour la seconde partie de
l'intégration.
