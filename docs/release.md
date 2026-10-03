# Build release (lot 2.1 de la revue de code)

## Ce qui est en place
- **R8** (`isMinifyEnabled` + `isShrinkResources`) sur le build release, règles dans `app/proguard-rules.pro`
  (JNI sherpa-onnx, LiteRT-LM, SQLCipher conservés). Règles larges par prudence, non encore validées sur appareil.
- **versionCode** : variable `VERSION_CODE`, sinon `GITHUB_RUN_NUMBER` (automatique sur GitHub Actions), sinon 1.
- **Signature** : seulement si `TAIPO_KEYSTORE_PATH` est défini ; sinon l'APK release n'est pas signé.
- **Gradle** : `org.gradle.caching=true`. Version de Room unique dans `gradle/libs.versions.toml`.

## Secrets GitHub à créer pour signer (décision D6 : Play App Signing recommandé, clé d'**upload**)
`TAIPO_KEYSTORE_BASE64` (keystore encodé en base64), `TAIPO_KEYSTORE_PASSWORD`, `TAIPO_KEY_ALIAS`, `TAIPO_KEY_PASSWORD`.

Étape à ajouter dans `.github/workflows/android.yml` avant `./gradlew assembleRelease` :

```yaml
- name: Préparer la signature
  if: steps.variant.outputs.variant == 'release'   # adapter au nom réel de l'étape qui fixe la variante
  run: |
    echo "${{ secrets.TAIPO_KEYSTORE_BASE64 }}" | base64 -d > "$RUNNER_TEMP/taipo.jks"
    echo "TAIPO_KEYSTORE_PATH=$RUNNER_TEMP/taipo.jks" >> "$GITHUB_ENV"
    echo "TAIPO_KEYSTORE_PASSWORD=${{ secrets.TAIPO_KEYSTORE_PASSWORD }}" >> "$GITHUB_ENV"
    echo "TAIPO_KEY_ALIAS=${{ secrets.TAIPO_KEY_ALIAS }}" >> "$GITHUB_ENV"
    echo "TAIPO_KEY_PASSWORD=${{ secrets.TAIPO_KEY_PASSWORD }}" >> "$GITHUB_ENV"
```

## Checklist de validation d'un APK release (à faire avant toute diffusion)
- [ ] `./gradlew assembleRelease` réussit (sinon : ajouter les `-dontwarn` réclamés par R8).
- [ ] Installer l'APK release sur l'appareil, activer le clavier.
- [ ] Frappe, suggestions, autocorrection ; dictionnaire personnel (base chiffrée) ; presse-papiers.
- [ ] Correction IA, mode prompt (streaming), dictée (sherpa-onnx).
- [ ] Noter la taille de l'APK debug et release.

## À essayer séparément
- `org.gradle.configuration-cache=true` : non activé (le Kotlin surclassé en 2.4.0 dans le build racine et
  KSP/Room peuvent poser problème) ; à tester seul, build propre puis build suivant.
