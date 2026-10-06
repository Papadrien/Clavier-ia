# Build release (lot 2.1 de la revue de code)

## Ce qui est en place
- **R8** (`isMinifyEnabled` + `isShrinkResources`) sur le build release, règles dans `app/proguard-rules.pro`
  (JNI sherpa-onnx, LiteRT-LM, SQLCipher conservés). Règles larges par prudence, non encore validées sur appareil.
- **versionCode** : variable `VERSION_CODE`, sinon `GITHUB_RUN_NUMBER` (automatique sur GitHub Actions), sinon 1.
- **Signature** : seulement si `TAIPO_KEYSTORE_PATH` est défini ; sinon l'APK release n'est pas signé.
- **Gradle** : `org.gradle.caching=true`. Versions de Room (unique) et de `litertlm-android` (figée à 0.17.1) dans `gradle/libs.versions.toml`.

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

## Mises à jour sans réinstaller (debug et release)
- **Debug** : signé avec `app/debug.keystore` (versionné, mot de passe public `android`, sans valeur de sécurité),
  identique en local et en CI. Les APK debug se mettent donc à jour par-dessus. Ne jamais supprimer ni régénérer
  ce fichier : changer la clé impose une dernière réinstallation sur chaque appareil.
- **Release** : mise à jour possible tant que la clé (secrets `TAIPO_KEYSTORE_*`) reste la même et que le
  `versionCode` augmente (numéro d'exécution GitHub Actions). Si les secrets sont absents, l'APK n'est pas signé
  donc non installable.
- **Passer de debug à release (ou l'inverse)** : signatures différentes, donc désinstallation obligatoire
  (même identifiant `fr.junade.taipo`). Les données (dictionnaire chiffré par Keystore) sont perdues dans ce cas.
- **Build local vs CI** : en local `versionCode` = 1 ; installer un APK local par-dessus un APK CI est un
  « downgrade » refusé hors adb (`adb install -r -d` l'autorise en debug).
