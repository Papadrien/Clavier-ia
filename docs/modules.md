# Modules et langue de l'interface (lot 4.4, 04/10/2026)

## A5 : module `:core` (JVM pur)

Le projet compte deux modules Gradle :

| Module | Type | Contenu |
|---|---|---|
| `:app` | application Android | tout le reste : service IME, vues, activités, Room + SQLCipher, IA, dictée |
| `:core` | bibliothèque **JVM** (`org.jetbrains.kotlin.jvm`) | logique pure, sans aucune API Android ni coroutines |

Contenu de `:core` (`core/src/main/kotlin/`, **mêmes packages qu'avant** : aucun import n'a changé) :

- `fr.junade.taipo.KeyboardLanguage` (l'enum FR / EN, sortie de `KeyboardLayout.kt`) ;
- `fr.junade.taipo.dictionary` : `Dictionary` (autocorrection, suggestions), `InflectionRules`, `PersonalDictionary`
  (règles de normalisation), `WordSuggestion` ;
- `fr.junade.taipo.suggestion` : `NextWordModel`, `NextWordCrypto`, `AtomicFileWrite` (`writeAtomically`), `WordText`,
  `EmojiSuggester`.

Seuls changements de code : `writeAtomically` et `NextWordCrypto` passent de `internal` à publics (ils sont utilisés par
`NextWordRepository`, qui reste dans `:app`). Les deux fonctions `internal` de `Dictionary` ne servent qu'aux tests, qui
ont suivi dans `:core`.

Tests déplacés dans `core/src/test/kotlin/` (JUnit 5, comme avant) : `DictionaryAccentsTest`, `DictionaryBoundedDistanceTest`,
`DictionarySuggestionsTest`, `DictionaryTest`, `InflectionRulesTest`, `PersonalDictionaryTest`, `AtomicFileWriteTest`,
`NextWordCryptoTest`, `NextWordModelTest`, `WordTextTest`.

### Ce qui est volontairement resté dans `:app`

- `PersonalDictionaryRepository` (et son test) : il dépend de `PersonalWordDao` / `PersonalWordEntity`, annotés Room. Le sortir
  demanderait une interface de stockage dans `:core` plus un adaptateur Room : c'est un changement de structure de la
  persistance, hors du périmètre « déplacement mécanique » du lot. Piste si on veut aller plus loin.
- `DictionaryLoader`, `EmojiSuggesterLoader`, `NextWordRepository`, `DatabasePassphraseProvider`, `SuggestionPolicy` (Context,
  assets, Keystore, `InputType`).
- `DictionaryAssetsIntegrityTest` et `EmojiSuggesterTest` : ils lisent les assets de `app/src/main/assets` par chemin relatif.

### Pour lancer les tests

- `./gradlew :core:test` : seulement `:core` (rapide, sans Android).
- `./gradlew testDebugUnitTest` : inchangé pour la CI. `:core` déclare une tâche `testDebugUnitTest` qui renvoie vers `test`,
  donc les tests de `:core` sont inclus.
- ktlint et detekt (`docs/qualite.md`) analysent aussi `core/`.

## U4 / D4 : langue de l'interface

**Décision D4 (option par défaut du plan) : interface 100 % française, assumée.** Les écrans, libellés et messages sont
uniquement en français dans `app/src/main/res/values/strings.xml` ; il n'y a pas de dossier `values-en`. Ce n'est pas un oubli :

- la langue de **frappe** du clavier (FR / EN, disposition, dictionnaires, dictée) est indépendante de la langue de l'**interface** ;
- la traduction de l'interface n'est pas dans le périmètre de la V1 ; elle viendra avec les langues de la V3 si la décision change.

Pour la reconsidérer plus tard : les libellés sont déjà dans `strings.xml` (aucune action de code requise pour ajouter
`values-en/strings.xml`). Reste à vérifier à ce moment-là les chaînes construites en dur dans le code.
