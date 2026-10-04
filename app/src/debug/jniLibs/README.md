# app/src/debug/jniLibs/ — émulateur x86_64 (lot 4.1, B7)

Facultatif. Le build debug accepte l'ABI **x86_64** (émulateur Android sur PC) ; la release reste en arm64-v8a
seulement (`ndk.abiFilters` dans `app/build.gradle.kts`).

Pour que la **dictée** fonctionne aussi sur l'émulateur, copier ici, dans `x86_64/`, les 4 bibliothèques
sherpa-onnx de l'ABI x86_64 (même archive de release que pour arm64, voir `app/src/main/jniLibs/README.md`) :

```
app/src/debug/jniLibs/x86_64/
  libsherpa-onnx-jni.so
  libsherpa-onnx-c-api.so
  libsherpa-onnx-cxx-api.so
  libonnxruntime.so
```

Sans elles, le clavier fonctionne normalement sur l'émulateur (SQLCipher fournit déjà x86_64) ; seule la dictée
échoue, avec le message d'erreur habituel (le chargement natif est rattrapé dans `VoiceController`).
Le moteur IA (LiteRT-LM) est testé sur appareil réel : ne pas s'attendre à une correction IA sur émulateur.
