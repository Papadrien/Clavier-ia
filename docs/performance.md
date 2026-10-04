# Performance mesurée (lot 3.6, P5)

Ajouté le 04/10/2026. **Non compilé ni exécuté** depuis l'environnement de développement (pas de compilateur,
pas d'appareil) : tout est à valider une fois sur le Pixel 9. Les versions `androidx.benchmark:benchmark-macro-junit4`
(1.4.1), `androidx.test.uiautomator` (2.3.0) et `androidx.profileinstaller` (1.4.1) sont à confirmer au premier
build (catalogue `gradle/libs.versions.toml`).

## Ce qui a été posé

- **Sections de trace** (`Tracing.kt`, objet `Sections`) : `Taipo.startInputView` (ouverture d'un champ),
  `Taipo.dictionaryLoad` (lecture + indexation d'un dictionnaire, hors thread principal), `Taipo.suggestions`
  (calcul de la bande de mots), `Taipo.autocorrection` (correction à l'espace). Visibles dans Perfetto, quasi
  gratuites sans trace. Aucun changement de comportement.
- **Variante `benchmark` de l'app** : copie de la release (R8 compris), signée avec la clé debug, avec
  `<profileable>` (`app/src/benchmark/AndroidManifest.xml`, absent de la release). Jamais publiée.
- **Module `:macrobenchmark`**, chargé **seulement sur demande** pour ne jamais gêner le build normal :
  `-Ptaipo.benchmark=true`, ou décommenter `taipo.benchmark=true` dans `gradle.properties` (nécessaire pour
  Android Studio).
  - `StartupBenchmark` : démarrage à froid, sans / avec profil.
  - `KeyboardBenchmark` : ouverture du clavier dans le champ de « Dictionnaire personnel » + frappe, sans / avec
    profil ; fluidité (`FrameTimingMetric`) et durée des 4 sections.
  - `BaselineProfileGenerator` : génère le profil de démarrage.
- `androidx.profileinstaller` ajouté à l'app (installe le profil hors Play Store ; sans fichier de profil, il ne fait rien).

## Mode d'emploi (Pixel 9 branché, écran déverrouillé, batterie > 50 %)

1. Mesures de base : `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Ptaipo.benchmark=true`.
   Les résultats (JSON + traces `.perfetto-trace`) sont dans `macrobenchmark/build/outputs/`. Consigner ici les
   médianes (démarrage, `dictionaryLoad`, `suggestions`, `autocorrection`, images lentes). Aucun budget chiffré
   imposé (décision antérieure) : on compare avant / après.
2. Profil : lancer seulement `BaselineProfileGenerator`
   (`-Pandroid.testInstrumentationRunnerArguments.class=fr.junade.taipo.macrobenchmark.BaselineProfileGenerator`),
   récupérer le fichier `*-baseline-prof.txt` dans le dossier de résultats, le copier en
   `app/src/main/baseline-prof.txt`, puis relancer l'étape 1 : l'écart « avec profil » / « sans profil » est le gain.
3. Vérifier ensuite une release normale (`assembleRelease`) : le profil est embarqué automatiquement par AGP.

## Points d'attention

- **Frappe approximative** : les touches sont dessinées sur une vue unique (pas de noeuds d'accessibilité) ; `typeSomeKeys`
  clique à des positions en fraction de l'écran. Vérifier à l'oeil, une fois, que des lettres arrivent dans le champ
  (sinon ajuster `rows` / `columns` dans `Targets.kt`).
- Le clavier est activé / choisi par `ime enable` + `ime set` (composant `fr.junade.taipo/.TaipoIme`) : il reste le clavier
  courant après les tests ; le rechoisir au besoin.
- Si le nom d'une section change dans l'app, le changer aussi dans `Targets.kt`.
- Ne pas comparer des mesures prises sur des conditions différentes (température, batterie, autres applis).
- Un profil `baseline-prof.txt` mal formé ferait échouer la release : ne pas l'écrire à la main, le générer.

## Reste à faire

- [ ] `./gradlew assembleDebug testDebugUnitTest` (le code applicatif touché : `Tracing.kt`, `DictionaryLoader`,
      `SuggestionController`, `TaipoIme.onStartInputView`, `app/build.gradle.kts`).
- [ ] `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Ptaipo.benchmark=true` sur le Pixel 9, consigner les mesures.
- [ ] Générer et committer `app/src/main/baseline-prof.txt`.
