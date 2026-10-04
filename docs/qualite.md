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
