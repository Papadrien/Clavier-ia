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

## Refonte graphique, lot 21 : performance (05/10/2026)

Audit du code de dessin après les lots 01 à 20, par relecture. **Non compilé ni exécuté** (pas de compilateur ni d'appareil
dans l'environnement de développement) : aucune mesure n'a été prise, les gains ci-dessous sont des allocations et des accès
évités, pas des millisecondes. À valider au premier build puis sur le Pixel 9.

### Ce qui était déjà bon (rien à changer)
- Aucun `Bitmap`, aucune image : seules les icônes du lanceur (PNG, imposées par Android) ; icônes du clavier en `VectorDrawable`.
- Aucun `Paint`, `Path` ni `Drawable` créé dans `onDraw` / `draw` : `KeyboardView`, `EmojiGridView`, `EmojiTabsView`,
  `PillKeyDrawable`, `RoundKeyDrawable` et `KeyboardBackgroundDrawable` préparent tout à la construction (`RectF` réutilisés).
- Polices Open Sans : 3 fichiers (≈ 130 Ko chacun), chargés une fois par processus (`TaipoType`), jamais dans `onDraw`.
- Fonds des barres : `PromptBarView` et `SuggestionStripView` créent leurs fonds une fois ; `renderZone` ne les change que
  quand l'état change.
- Release : R8 et `shrinkResources` actifs ; profil de démarrage prévu (voir plus haut).

### Corrigé
| Où | Problème | Correction |
| --- | --- | --- |
| `KeyboardView.onDraw` | chaque touche relisait 4 à 7 dimensions dans les ressources à chaque dessin (de l'ordre de 150 accès) | `DrawDimens` : lues une fois, relues à un changement de configuration ou de taille |
| `KeyboardView` (Maj actif, indices d'appui long, bulles) | une `String` créée par touche et par dessin (`uppercaseChar().toString()`, `char.toString()`) | dessin par un tampon `CharArray(1)` (`drawChar`) ; libellés non modifiés inchangés |
| `KeyboardView.keyboardWidth()` | un objet `KeyboardWidth` par appel (dessin, chaque toucher) | mis en cache, invalidé à un changement de taille ou de configuration |
| `KeyboardView.keyBounds()` | un `RectF` par dessin de la bulle d'agrandissement | `RectF` réutilisé |
| `KeyboardView.autoSizeTextPaint` / indices / bulles | `textSize` reposé sur le `Paint` à chaque touche | reposé seulement s'il change |
| `EmojiCatalog.load` (ouverture du panneau Emoji) | lecture + `hasGlyph` sur plusieurs milliers d'emojis **sur le fil principal à la première ouverture** | préchargé en tâche de fond dans `TaipoIme.onCreate` ; si le panneau s'ouvre avant la fin, il attend le même chargement (jamais pire qu'avant). Nouvelle section de trace `Taipo.emojiCatalog` |
| `CorrectionBarView` (vocal, menus), `SuggestionZoneView` (bouton Smart Clipboard) | un nouveau `Drawable` à chaque changement d'état | `ToggleBackgrounds` (un fond par état, créé au premier besoin) et cache des fonds du bouton vocal |
| `RecentEmojiBarView.setEmojis` | dimensions et taille de texte relues pour chaque emoji | lues une fois par appel |
| `fixedHeightTextDimen` | un `TypedValue` par appel pour lire le plafond de police (constant) | lu une fois |

Aucun comportement ni aucun rendu ne change : mêmes valeurs, mêmes formes, même zone tactile.

### Vu et laissé tel quel
- `ClipboardPanelView.setItems` reconstruit toutes les cartes à chaque changement : borné (20 entrées au plus + épinglés) et
  hors dessin ; un `RecyclerView` serait un changement d'architecture, hors du périmètre d'une refonte graphique.
- `EmojiTabsView.onDraw` lit encore 4 dimensions par dessin : il ne se redessine qu'au changement d'onglet.
- `ChatZoneView` crée un `GradientDrawable` par bulle : une fois par message, pas par jeton reçu.
- Polices : 3 × 130 Ko dans l'APK ; un sous-ensemble (latin) les réduirait, à décider avec la taille de l'APK en vue.

### À mesurer sur le Pixel 9 (rien n'est mesuré)
Mêmes conditions avant / après (batterie, température, autres applis), release ou variante `benchmark`.
1. Démarrage à froid et premier affichage : `StartupBenchmark` et `KeyboardBenchmark` (voir plus haut), section
   `Taipo.startInputView`.
2. Fluidité de frappe, animations : `FrameTimingMetric` du `KeyboardBenchmark` ; à l'oeil avec « Profile GPU rendering »
   (Options pour les développeurs) en tapant vite avec Maj actif.
3. Ouverture du panneau Emoji (la première fois après le démarrage, c'est le cas corrigé), du Smart Clipboard, de la barre IA :
   Perfetto, ou `adb shell dumpsys gfxinfo fr.junade.taipo framestats` juste après l'ouverture.
4. Mémoire : `adb shell dumpsys meminfo fr.junade.taipo` au repos, clavier ouvert, panneau Emoji ouvert, modèle IA chargé ;
   comparer à la version avant refonte (lot 14 : `Taipo-complet-52`) si disponible.
5. Vérifier à l'oeil que rien n'a bougé : lettres en majuscules (Maj et verrouillage), indices de chiffres, bulle
   d'agrandissement, bulle d'accents, rotation portrait / paysage (les dimensions sont relues à ce moment).

## Reste à faire

- [ ] `./gradlew assembleDebug testDebugUnitTest` (le code applicatif touché : `Tracing.kt`, `DictionaryLoader`,
      `SuggestionController`, `TaipoIme.onStartInputView`, `app/build.gradle.kts`).
- [ ] `./gradlew :macrobenchmark:connectedBenchmarkAndroidTest -Ptaipo.benchmark=true` sur le Pixel 9, consigner les mesures.
- [ ] Générer et committer `app/src/main/baseline-prof.txt`.
- [ ] Lot 21 : `./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt` (fichiers touchés : `KeyboardView`, `BarStyle`,
      `CorrectionBarView`, `SuggestionZoneView`, `RecentEmojiBarView`, `TaipoDimens`, `EmojiCatalog`, `TaipoIme`, `Tracing`,
      `KeyboardBenchmark`, `Targets`) puis les mesures ci-dessus.
