# Qualité du code : ktlint et detekt (lot 4.1, B8)

Ajouté le 04/10/2026. **Non exécuté** depuis l'environnement de développement (pas de réseau ni de compilateur) :
les versions (`ktlint-cli` 1.5.0, `detekt-cli` 1.23.8, dans `gradle/quality.gradle.kts`) et la première analyse sont
à valider une fois.

## Principe

- **Aucun plugin Gradle** : ktlint et detekt sont lancés en ligne de commande par des tâches `JavaExec`
  (`gradle/quality.gradle.kts`, appliqué par le `build.gradle.kts` racine). Rien n'est téléchargé ni résolu tant
  qu'on ne lance pas une de ces tâches : `assembleDebug`, `assembleRelease` et les tests ne sont pas touchés.
- **Baseline** : la dette existante est figée dans `quality/ktlint-baseline.xml` et `quality/detekt-baseline.xml`.
  Une alerte déjà dans la baseline est tolérée ; **toute nouvelle alerte fait échouer la tâche**. C'est la règle
  « sur les nouveaux fichiers » du plan : le code existant n'est pas à nettoyer d'un coup, le nouveau code l'est.
- Le code sherpa-onnx recopié du dépôt officiel (`com/k2fsa/**`) n'est jamais analysé.
- Réglages : `.editorconfig` (style `android_studio`, lignes à 160) et `config/detekt/detekt.yml`
  (règles de bruit désactivées : nombres magiques, nombre de fonctions, etc.).

## Mode d'emploi

1. **Une fois** : `./gradlew ktlintBaseline detektBaseline`, puis committer le dossier `quality/`.
2. Au quotidien : `./gradlew qualityCheck` (= `ktlintCheck` + `detekt`).
3. Formatage automatique : `./gradlew ktlintFormat` (relire le diff avant de committer).
4. Après un gros nettoyage : régénérer la baseline (étape 1) pour retirer les entrées corrigées.

## CI (à ajouter à la main dans `.github/workflows/android.yml`, non présent dans l'archive)

```yaml
- name: Qualité du code
  run: ./gradlew qualityCheck
```

## Points d'attention

- detekt 1.23.x embarque un compilateur Kotlin plus ancien que celui du projet (2.4.0) : si une syntaxe récente
  produit une erreur d'analyse, passer à une version de detekt plus récente dans `gradle/quality.gradle.kts`.
- Si ktlint ou detekt refuse un argument (versions différentes), lire `--help` de la version résolue et ajuster
  les `args` de `gradle/quality.gradle.kts`.
- La tâche `ktlintCheck` crée elle-même la baseline si elle n'existe pas encore : exécuter l'étape 1 d'abord pour
  que la première exécution soit explicite.

## Reste à faire

- [ ] `./gradlew ktlintBaseline detektBaseline`, committer `quality/`.
- [ ] `./gradlew qualityCheck` vert.
- [ ] Étape CI.

## Découpage des gros fichiers (audit 2.1), par étapes
Audit du 14/10/2026 : `TaipoIme` (~1 840 lignes), `Dictionary`, `KeyboardView`, `CorrectionAiController`, `SuggestionController`,
`PromptModeController`, `CorrectionBarView` sont trop gros. Méthode : une extraction par livraison, projet compilable à chaque
étape, comportement inchangé (mêmes appels, même ordre), logique pure testée sur la JVM quand elle s'y prête. Même modèle que
`ClipboardController` / `VoiceController` : une classe avec une interface `Host` que `TaipoIme` implémente en objet anonyme.

- [x] Étape 1 (15/10/2026) : glissement de suppression (story 1.9) → `DeleteSwipeController` (+ `DeleteSwipeControllerTest`).
      `TaipoIme` passe de 1 839 à ~1 700 lignes.
- [x] Étape 2 (15/10/2026) : emojis (panneau, barre des récents, insertion, emoji suggéré, retour arrière du panneau) →
      `EmojiController` (+ `EmojiControllerTest` : état de la barre des récents, espace avant l'emoji). `TaipoIme` passe de ~1 700 à ~1 615 lignes.
- [x] Étape 3 (15/10/2026) : auto-remplissage en ligne (Bitwarden, etc.) → `InlineSuggestionsController` (code déplacé tel quel,
      mêmes journaux et même étiquette `TaipoIme` ; pas de test JVM : tout dépend d'Android). `TaipoIme` passe à ~1 530 lignes.
- [x] Étape 4 (15/10/2026) : pop-ups (message avec OK, liste des langues) → `MessagePopup` ; le jeton de fenêtre, le repli en toast
      et le repli « langue suivante » sont inchangés. `TaipoIme` passe à ~1 475 lignes.
- [x] Étape 5 (15/10/2026) : hôtes regroupés dans `ImeHosts.kt` : un contexte commun `ImeHostContext` (implémenté une seule fois
      dans `TaipoIme`, par délégation) et 12 classes d'hôtes (`EmojiHost`, `ClipboardHost`, `SuggestionHost`, `MessagePopupHost`,
      `PromptHost`, `CorrectionHost`, `UndoHost`, `VoiceHost`, `AiTranscriptionHost`, `DeleteSwipeHost`, `ViewComposerHost`,
      `InlineSuggestionsHost`) qui remplacent les objets anonymes. Mêmes appels, même ordre. `TaipoIme` passe de ~1 475 à ~1 275 lignes.
- [x] Étape 6 (15/10/2026) : `Dictionary` (core) : distances d'édition, inversion de lettres et masque de lettres → `EditDistance` ;
      accents, majuscule initiale et lecture des listes de fréquences → `WordForms` (+ `EditDistanceAndWordFormsTest`). `Dictionary`
      garde des relais de même nom : l'API, les tests existants et les appels de l'app sont inchangés. `Dictionary.kt` passe de
      1 058 à ~890 lignes ; le cœur (recherche, suggestions, flexions, élisions) reste dans la classe, car il partage tout son état.
- [x] Étape 7 (15/10/2026) : `KeyboardView` : bulle de symboles de l'appui long (géométrie, cellule sélectionnée, relâchement, dessin,
      globe de la touche Entrée) → `KeyPopup` (code déplacé tel quel ; pas de test JVM : dépend d'Android). La vue garde les gestes et
      fournit à la bulle la touche, la place disponible et la zone des touches. `KeyboardView` passe de 1 051 à ~910 lignes.
- [x] Étape 8 (15/10/2026) : `KeyboardView` : bulle d'agrandissement de la touche pressée (story 1.10) → `KeyPreview` (code déplacé tel
      quel ; la vue choisit la touche et fournit sa position, son libellé et les dimensions). `KeyboardView` passe à ~885 lignes.
- [x] Étape 9 (15/10/2026) : `KeyboardView` : dessin des touches (faces, ombres, icônes, libellés, indice d'appui long, styles) →
      `KeyRenderer` (+ `DrawDimens`, dimensions de la charte, sorties en classe de premier niveau). Code déplacé tel quel, sans
      allocation au dessin ; la vue passe à la méthode `draw` l'état utile (dimensions, disposition, Maj, verrouillage, touche
      pressée). `KeyboardView` passe de 1 051 à ~640 lignes au total depuis le début (étapes 7 à 9).
- [x] Étape 10 (15/10/2026) : `Dictionary` : suggestions de la bande (`suggestionsFor`, `suggestionSlotsFor`, `placementSlotsFor`,
      `alternativesFor`, complétions) → `DictionarySuggestions`. `Dictionary` garde les mêmes méthodes publiques (relais) ; les
      listes et l'état dont les suggestions ont besoin passent de `private` à `internal` (module `core` seulement). La boucle
      chaude de `alternativesFor` lit les tableaux une fois, hors de la boucle. `Dictionary.kt` passe de 1 058 à ~700 lignes.
- [x] Découpage terminé : plus aucun fichier de l'audit 2.1 n'est une « classe divine » (`TaipoIme` ~1 280, `KeyboardView` ~640,
      `Dictionary` ~700). Restent proches de 500-680 lignes : `CorrectionAiController`, `SuggestionController`,
      `PromptModeController`, `CorrectionBarView` : à découper seulement si une évolution les touche.
