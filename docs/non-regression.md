# Non-régression de la refonte graphique (lot 22)

Ajouté le 05/10/2026. **Rien n'a été exécuté** depuis l'environnement de développement (ni compilateur, ni appareil) :
les tests ci-dessous sont à lancer une fois. Seules les vérifications de ressources ont été rejouées à part, par un script
équivalent, sur l'archive : elles passeraient (aucune référence manquante, aucune image raster, palette de nuit cohérente).

## Lancer

```
./gradlew :core:test :app:testDebugUnitTest      # JVM : tous les tests existants + les 2 nouveaux
./gradlew :app:connectedDebugAndroidTest         # appareil (Pixel 9) : Room/SQLCipher + KeyboardView
```

## Nouveaux tests du lot

| Fichier | Rôle |
| --- | --- |
| `app/src/test/.../KeyboardCatalogRegressionTest.kt` | Toutes les combinaisons disposition × langue × rangée de chiffres × type de champ : identifiants uniques, libellés = caractère saisi, Entrée / Effacer / Espace / Maj / bascule / emoji présents où il faut, bulles sans doublon. |
| `app/src/test/.../RefonteResourcesRegressionTest.kt` | Règles du plan : aucune image raster dans `drawable*` (seule exception : le logo `taipo_logo_horizontal`), Open Sans (3 graisses + fichiers), toute ressource référencée (XML et Kotlin) existe, palette de nuit ⊆ palette de jour, dimensions valides. |
| `app/src/androidTest/.../KeyboardViewRegressionTest.kt` | Sur appareil : toutes les dispositions se dessinent (Maj et verrouillage compris), zones tactiles sans chevauchement, un appui saisit chaque touche, couleurs de la charte réellement dessinées, touche pressée visible puis rétablie, glissements espace et retour arrière, appui long (accents, bulle du point), annulation, deux doigts. |

## Couverture automatisée de la liste du plan

| Point du plan | Tests |
| --- | --- |
| Lettres, chiffres, ponctuation | `KeyboardLayoutTest`, `KeyboardControllerTest`, `KeyCharsTest`, `KeyboardCatalogRegressionTest`, `KeyboardViewRegressionTest` |
| Espace, double espace | `KeyboardControllerTest` |
| Swipe espace / swipe retour arrière | `SpaceSwipeTrackerTest`, `CursorStepsTest`, `BackspaceSwipeTrackerTest`, `KeyboardViewRegressionTest` |
| Retour arrière, Entrée | `KeyboardControllerTest`, `KeyboardViewRegressionTest` |
| Maj, majuscules, verrouillage | `KeyboardControllerTest`, `KeyboardViewRegressionTest` (rendu) |
| Caractères spéciaux, accents, popup | `KeyboardLayoutTest`, `PopupPlacementTest`, `KeyboardViewRegressionTest` |
| Emoji | `EmojiCatalogTest`, `EmojiGridLayoutTest`, `EmojiTextTest`, `RecentEmojisTest`, `MessagingFieldPolicyTest` |
| Correction | `CorrectionDiffTest`, `CorrectionPromptTest`, `CorrectionSafeguardTest`, `SentenceCorrectionTest`, `ProtectedWordsTest` |
| Génération, chat | `PromptConversationTest`, `PromptInputBufferTest`, `PromptBufferVoiceFieldTest`, `PromptCaretMapTest`, `GenerationPromptTest`, `SystemPromptEditTest`, `ChatTranscriptTest` |
| Dictionnaire | tests `core/…/dictionary`, `DictionaryAssetsIntegrityTest`, `PersonalDictionaryRepositoryTest`, `PersonalDictionaryDatabaseTest` (appareil) |
| Clipboard | `Clip*Test`, `ClipboardItemsTest`, `SensitiveContentDetectorTest`, `ClipboardDatabaseTest` (appareil) |
| Modèles | `AiModelTest`, `Sha256Test` |
| Suggestions d'auto-remplissage (Bitwarden…) | `InlineSuggestionColorsTest` (couleurs selon le thème) ; l'aspect réel se vérifie à la main avec un gestionnaire de mots de passe |

**Sans test automatisé** (à passer à la main) : le bouton microphone et la dictée (`VoiceEngine`, `VoiceController`, y compris le micro de la barre du prompt : appui bref, appui long, sortie du mode pendant l'écoute), le stop
d'une génération, les messages d'erreur de l'IA, les préférences (`KeyboardPreferences`, réglages), l'aspect des écrans
(captures non comparées automatiquement).

## Recette manuelle sur le Pixel 9 (clavier de la refonte)

À faire en clair puis avec la police à 130 %, en portrait puis en paysage.

1. **Clavier** : taper une phrase avec lettres, chiffres (rangée activée puis désactivée : appui long), ponctuation, apostrophe ; Maj une fois, deux fois (verrouillage), retour ; symboles puis ABC.
2. **Espace** : appui (espace saisi), double espace (point), glisser (curseur), pas d'espace saisi après un glissement.
   Autocorrection contextuelle (français) : « a demain » -> « à demain », « quelque chose a manger » -> « à manger », « il a manger » -> « il a mangé », « il faut mangé » -> « manger » ; « il a mangé » et « à côté » inchangés ; retour arrière juste après annule toute la correction.
   Curseur placé dans un mot (tap) : la barre propose des corrections du mot entier ; en toucher une remplace le mot entier. Mot corrigé (espace ou suggestion) alors qu'une espace suit déjà : pas de double espace. Mêmes vérifications dans le prompt.
3. **Retour arrière** : appui, appui maintenu (répétition), glisser vers la gauche (mots surlignés, suppression au relâchement).
4. **Accents** : appui long sur e, a, o, u, c, puis sur le point ; choisir un accent en glissant ; relâcher sans bouger.
5. **Entrée** : action du champ (envoyer, rechercher, retour à la ligne) ; champs e-mail, URL, nombre, téléphone (touches adaptées).
6. **Emoji** : ouvrir, changer d'onglet, saisir, récents, revenir au clavier.
7. **Microphone** : dicter une phrase, vérifier le texte inséré, stopper.
8. **IA** : correction, génération (puis stop en cours), chat, erreur provoquée (modèle absent ou supprimé).
9. **Données** : mot ajouté au dictionnaire personnel (suggéré ensuite), élément du presse-papiers copié, épinglé, collé ; réglage modifié puis app relancée ; changement de modèle.
10. **Visuel** : touche pressée enfoncée puis relevée, barre grise de l'espace, Entrée ronde violette, aucune touche rognée.

## Reste à faire

- [ ] `./gradlew :core:test :app:testDebugUnitTest` vert.
- [ ] `./gradlew :app:connectedDebugAndroidTest` vert sur le Pixel 9 (si un test de rendu échoue, noter lequel : certains reposent sur des détails du dessin non vérifiés).
- [ ] Recette manuelle ci-dessus.
