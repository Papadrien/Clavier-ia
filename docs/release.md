# Build release (lot 2.1 de la revue de code)

## Ce qui est en place
- **R8** (`isMinifyEnabled` + `isShrinkResources`) sur le build release, règles dans `app/proguard-rules.pro`
  (JNI sherpa-onnx, LiteRT-LM, SQLCipher conservés). Règles larges par prudence, non encore validées sur appareil.
- **versionCode** : variable `VERSION_CODE`, sinon `GITHUB_RUN_NUMBER` (automatique sur GitHub Actions), sinon 1.
- **Signature** : seulement si `key.properties` (racine du dépôt) ou `TAIPO_KEYSTORE_PATH` est défini ; sinon l'APK release n'est pas signé.
- **Gradle** : `org.gradle.caching=true`. Versions de Room (unique) et de `litertlm-android` (figée à 0.17.1) dans `gradle/libs.versions.toml`.

## Secrets GitHub pour signer (décision D6 : Play App Signing recommandé, clé d'**upload**)
Les mêmes que AnimalTimer : `ANDROID_KEYSTORE_BASE64` (keystore encodé en base64) et `ANDROID_KEY_PROPERTIES`
(contenu de `key.properties` : `storePassword`, `keyPassword`, `keyAlias`, `storeFile=keystore.jks`).

L'étape « Setup Android signing » de `.github/workflows/android.yml` (variante release uniquement) écrit
`app/keystore.jks` et `key.properties` à la racine ; `app/build.gradle.kts` les lit. Sans secrets, l'étape génère un
keystore de secours (`taipo-debug`) : l'APK est alors signé avec une clé jetable, à ne jamais diffuser.
Les variables `TAIPO_KEYSTORE_*` restent acceptées en repli pour un build local.

## Checklist de validation d'un build release (à faire avant toute diffusion)
Play exige un **AAB** : `./gradlew bundleRelease` (sortie `app/build/outputs/bundle/release/`). Pour tester sur appareil,
`assembleRelease` (APK). Les deux utilisent les mêmes règles R8.
- [ ] `./gradlew assembleRelease bundleRelease` réussit (sinon : ajouter les `-dontwarn` réclamés par R8, voir `app/build/outputs/mapping/release/missing_rules.txt`).
- [ ] Garder `mapping.txt` (`app/build/outputs/mapping/release/`) de **chaque** version publiée (désobfuscation des plantages) ; l'AAB le contient aussi une fois importé dans la Play Console.
- [ ] Installer l'APK release signé (`adb install`), activer le clavier. Surveiller `adb logcat *:E` pendant tous les tests : aucune `ClassNotFoundException`, `NoSuchMethodError`, `NoSuchFieldError`, `UnsatisfiedLinkError`.
- [ ] Frappe dans plusieurs applications ; suggestions ; autocorrection ; changement de langue (appui long sur Entrée).
- [ ] Dictionnaire personnel et presse-papiers (bases chiffrées SQLCipher) : ajouter, redémarrer l'appareil, retrouver les données.
- [ ] Sauvegarde puis restauration (Mes données > Sauvegarde et restauration).
- [ ] Téléchargement d'un modèle (réseau mobile et Wi-Fi), reprise après coupure du réseau et après arrêt par le système (liste détaillée : `docs/telechargement-modeles.md`, point 1.7).
- [ ] Correction IA, mode prompt (streaming), dictée (sherpa-onnx) et transcription IA.
- [ ] Mesure d'usage (Aptabase) : activer puis désactiver, aucune erreur dans logcat.
- [ ] Demande d'avis Play (peut ne rien afficher hors Play) et écran de confidentialité.
- [ ] Noter la taille de l'APK debug et release, et de l'AAB.
- [ ] Contrôler l'alignement 16 Ko des `.so` de l'AAB (outil d'alignement des NDK/Android Studio ou Analyseur d'APK).

## À essayer séparément
- `org.gradle.configuration-cache=true` : non activé (le Kotlin surclassé en 2.4.0 dans le build racine et
  KSP/Room peuvent poser problème) ; à tester seul, build propre puis build suivant.

## Mises à jour sans réinstaller (debug et release)
- **Debug** : signé avec `app/debug.keystore` (versionné, mot de passe public `android`, sans valeur de sécurité),
  identique en local et en CI. Les APK debug se mettent donc à jour par-dessus. Ne jamais supprimer ni régénérer
  ce fichier : changer la clé impose une dernière réinstallation sur chaque appareil.
- **Release** : mise à jour possible tant que la clé (secrets `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEY_PROPERTIES`) reste la même et que le
  `versionCode` augmente (numéro d'exécution GitHub Actions). Si les secrets sont absents, l'APK n'est pas signé
  donc non installable.
- **Passer de debug à release (ou l'inverse)** : signatures différentes, donc désinstallation obligatoire
  (même identifiant `fr.junade.taipo`). Les données (dictionnaire chiffré par Keystore) sont perdues dans ce cas.
- **Build local vs CI** : en local `versionCode` = 1 ; installer un APK local par-dessus un APK CI est un
  « downgrade » refusé hors adb (`adb install -r -d` l'autorise en debug).
