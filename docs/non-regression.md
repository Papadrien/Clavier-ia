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

## Icône robot animée (17/10/2026)

`RobotIconDrawable` (Canvas, même tracé que `ic_robot.xml`) remplace l'icône du bouton robot : antenne qui raccourcit, yeux qui montent et se plissent, bouche qui apparaît quand le bouton passe à l'état actif (fond violet), et l'inverse au retour. 300 ms, `OvershootInterpolator(4)` (léger ressort dans les deux sens). Saut direct si la barre n'est pas encore affichée ou si les animations système sont coupées. Test JVM : `RobotIconPoseTest`. Rendu à valider sur Pixel 9 (rien compilé ni exécuté).

Le chevron du menu de gauche (`RotatingDrawable`) utilise le même ressort (`OvershootInterpolator(4)`, 320 ms) : il dépasse son demi-tour d'un peu avant de se stabiliser, à l'ouverture comme à la fermeture.

## Icône micro animée (17/10/2026)

`MicIconDrawable` (Canvas, même tracé que `ic_mic.xml`) remplace l'icône des deux boutons Vocal (barre normale et barre du prompt) et la roue `LoadingSpinnerDrawable` sur ces boutons. Seule la capsule s'anime ; le pied (U, tige, base) ne bouge jamais. Chargement du modèle **et** transcription (`LOADING`, `TRANSCRIBING`) : le contour de la capsule se dessine puis s'efface en boucle (2,6 s). Écoute (`RECORDING`) : une jauge remplit et vide la capsule selon le niveau sonore réel (`VoiceRecorder.onLevel`, RMS en décibels, lissé : attaque 50 ms, retombée 180 ms), avec un plancher de 8 % pour rester visible dans le silence. Passages d'un état à l'autre : ressort (~20 % de dépassement). L'animation s'arrête quand le bouton n'est pas visible ; avec les animations système coupées, pose directe. Tests JVM : `MicIconPoseTest`. Rendu à valider sur Pixel 9 (rien compilé ni exécuté).

## Icône Correction : coche décalée (17/10/2026)

`ic_spellcheck.xml` : la coche passe sous le A (elle démarre à x = 11 au lieu de 12,5 ; le A est décalé de 1,5 vers la droite pour garder l'icône centrée). Écart visible entre le pied droit du A et le début de la coche : 0,69 → 1,2 sur la grille de 24 (avec le trait de 2). Un décalage de 1 à 2 seulement vers la gauche ne séparait pas mieux (0,5 à 0,69). À valider sur Pixel 9.

## Icône Correction animée « Scanner » (17/10/2026)

`CorrectIconDrawable` (Canvas, même tracé que `ic_spellcheck.xml`) remplace l'icône du bouton Corriger et la roue `LoadingSpinnerDrawable` sur ce bouton. Chargement ou correction (`LOADING`, `CORRECTING`, `CANCELLING`) : l'icône est atténuée et un faisceau (aller-retour de 1,3 s) l'éclaire là où il passe ; le passage repos ↔ correction se fait avec un léger ressort. Correction **réussie** (passage `CORRECTING` → `IDLE`, état posé juste avant l'application du texte corrigé) : la coche s'efface puis se retrace avec un rebond (1,3 s). Annulation ou erreur (retour à `IDLE` depuis `CANCELLING` ou `LOADING`) : retour direct au repos, sans l'animation de fin. L'animation s'arrête quand le bouton n'est pas visible ; animations système coupées : faisceau figé au milieu, pas d'animation de fin. Tests JVM : `CorrectIconPoseTest`. Rendu à valider sur Pixel 9 (rien compilé ni exécuté).

## Icône Smart Clipboard animée « Étincelles » (17/10/2026)

`ClipboardIconDrawable` (Canvas, même tracé que `ic_paste.xml`) remplace l'icône du bouton Smart Clipboard de la zone de suggestions. Au repos : exactement l'icône d'origine. Panneau ouvert (fond violet) : le presse-papiers s'incline de 8° vers la gauche et trois étincelles à quatre branches (rayons 5, 2,6 et 2,8 sur la grille de 24, elles débordent de l'icône) jaillissent en tournant puis scintillent tant que le panneau reste ouvert ; à la fermeture elles rentrent et il se redresse. Passage avec un léger ressort ; animation arrêtée quand le bouton n'est pas visible ; animations système coupées : pose directe, sans scintillement. L'icône de la puce de collage (`pasteIcon`) ne change pas. Tests JVM : `ClipboardIconPoseTest`. Rendu à valider sur Pixel 9 (rien compilé ni exécuté), en particulier le débordement des étincelles hors du bouton.

## Icônes animées du mode prompt (18/10/2026)

- `ic_chat.xml` : bulle de discussion avec une étincelle pleine au centre, icône fixe (le bouton afficher/masquer le chat ne s'anime pas).
- `SendIconDrawable` (Canvas, grille de 24, proposition A « Orbite ») remplace la roue du bouton rond d'envoi et les icônes `ic_send` / `ic_stop` (supprimées) : trois étincelles à quatre branches, de la taille des points de la maquette (4,5 sur la grille), tournent en orbite pendant le chargement du modèle ou l'arrêt d'une génération ; l'orbite se replie, puis la flèche remonte d'en bas avec un ressort. À l'envoi la flèche décolle en s'étirant, une onde part du bouton et le bouton rebondit ; l'icône suivante (carré stop pendant la génération, flèche sinon) remonte avec un ressort. Dessin coupé au cercle de la face. Animations système coupées : poses directes, orbite figée. Tests JVM : `SendIconPoseTest`.
- `ChatZoneView.playRise()` : la zone de chat monte de 40 dp (`taipo_chat_rise_distance`) avec un ressort et un fondu quand elle devient visible (déclenché par `PromptModeController.applyViews`). Aussi rejoué à la recréation de la vue (rotation).
- Rien compilé ni exécuté : rendu à valider sur Pixel 9 (taille des étincelles, coupe au cercle, rebond du bouton).

### Étincelle du bouton chat et icône Générer (18/10/2026, suite)

- `ChatIconDrawable` (Canvas, même tracé que `ic_chat.xml`) équipe le bouton afficher/masquer le chat de la barre du prompt : bulle fixe, étincelle animée avec un ressort. Chat ouvert : l'étincelle apparaît en tournant (elle repart de zéro, un demi-tour en arrière). Chat fermé : elle tourne d'un quart de tour (même dessin, quatre branches identiques) en rétrécissant à 70 %. Pose directe au premier affichage et animations système coupées ; animation reprise au retour du bouton à l'écran. Tests JVM : `ChatIconPoseTest` (dans `SendIconPoseTest.kt`).
- `ic_rate_review.xml` (bouton Générer de la barre d'action) : le stylo est remplacé par la même étincelle pleine, icône fixe.
- Rendu à valider sur Pixel 9 (rien compilé ni exécuté).
