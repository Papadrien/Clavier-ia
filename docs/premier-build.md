# Premier build — validation sur appareil

Statut (03/10/2026, retour d'Adrien) : le socle IA (LiteRT-LM : correction, génération en streaming)
et la dictée (sherpa-onnx) ont tourné et ont été vérifiés sur appareil. Les avertissements
« à vérifier au premier build » ont donc été retirés des commentaires du code (lot 1.1 du plan de revue).

Reste à faire à la main (impossible depuis l'environnement de revue, sans build) :

- [ ] Committer `app/schemas/*.json` exportés par Room (voir `room { schemaDirectory(...) }`).
- [x] `litertlm-android` figé à **0.17.1** dans `gradle/libs.versions.toml` (lot 1.2, 03/10/2026). Vérifier après un
      build propre : `./gradlew :app:dependencies --configuration debugRuntimeClasspath` doit afficher `0.17.1`.

## Purge sherpa-onnx (lot 2.2, 03/10/2026)

18 fichiers Kotlin inutilisés supprimés de `com/k2fsa/sherpa/onnx/` (5 conservés, voir le README du dossier).
Suppression faite **sans compilateur** : aucune référence aux classes supprimées n'a été trouvée dans les
fichiers conservés ni dans le reste du code. À valider :

- [ ] `./gradlew assembleDebug testDebugUnitTest` (si le compilateur réclame un fichier supprimé, par exemple
      `VersionInfo.kt`, le recopier depuis `sherpa-onnx/kotlin-api/`).
- [ ] Dictée sur l'appareil.

## Découpage de TaipoIme — étape 1 : VoiceController (lot 2.3, 03/10/2026)

La dictée est sortie de `TaipoIme` (2 922 → 2 659 lignes) sans changement de comportement :
- `VoiceController` : bouton (appui bref / long), démarrage, arrêt, annulation, silence de 20 s ; l'IME lui
  parle via l'interface `VoiceController.Host`.
- `ai/VoiceTextSync` : logique pure d'insertion progressive (texte figé si l'utilisateur modifie le champ),
  testée en JVM avec un faux champ (`VoiceTextSyncTest`, 13 cas).
- Non compilé ni exécuté ici. Smoke test manuel à faire : dictée appui bref, appui long, arrêt auto après silence,
  dictée puis frappe au milieu, fermeture du clavier pendant l'écoute, refus de permission micro.

## Découpage de TaipoIme — étape 2 : ClipboardController (lot 2.3, 03/10/2026)

Le Smart Clipboard est sorti de `TaipoIme` (2 659 → 2 358 lignes) sans changement de comportement :
puce de collage et son expiration (2.2, 2.3), panneau, épinglage, modification, étiquettes, suppression,
historique d'1 h (2.5 à 2.9). L'IME lui parle via `ClipboardController.Host`. La logique pure
(`ClipboardItems`, `ClipboardSuggestionState`…) reste couverte par les tests existants, inchangés.
Non compilé ni exécuté ici. Smoke test manuel : copier un texte → puce → collage ; ouverture du panneau,
collage d'une carte ; épingler, modifier, étiqueter, supprimer ; champ mot de passe (pas de puce) ;
puce qui disparaît après 10 min ou à la frappe ; copie sensible (aperçu masqué) ; dictée/correction en cours.

## Découpage de TaipoIme — étape 3 : PromptModeController (lot 2.3, 03/10/2026)

Le mode prompt est sorti de `TaipoIme` (2 358 → 2 043 lignes) sans changement de comportement : état du mode,
tampon du prompt, conversation, génération en streaming, arrêt, ajout du texte au champ, préchargement du modèle,
vues (zone de chat, barre du prompt, bande de suggestions). L'IME lit `prompt.active` et `prompt.buffer` pour
aiguiller les frappes ; l'insertion dans le champ de l'application (« Ajouter le texte ») reste côté IME
(`insertGeneratedText`).

**Reste volontairement dans `TaipoIme`** : le traitement des touches en mode prompt (`onPromptKeyPressed`,
autocorrection et annulation, double espace, apprentissage). Il partage le code des suggestions et de
l'autocorrection avec le champ normal : il sera extrait avec `SuggestionController` (étape 5), pas avant.

Non compilé ni exécuté ici. Smoke test manuel : entrer/sortir du mode prompt, saisie avec suggestions et
autocorrection dans le prompt, envoi (bouton et Entrée), réponse en streaming, stop, croix pendant une génération,
bouton afficher/masquer le chat, « Ajouter le texte » (avec et sans sélection), changement de champ (conversation
conservée), fermeture du clavier (conversation effacée), échec du moteur (prompt rendu à la saisie), modèle absent.

## Robustesse de la capture vocale (lot 2.5, 03/10/2026)

`VoiceRecorder` (sans changement de comportement en usage normal) :
- **File bornée** : `Channel.UNLIMITED` remplacé par une file d'environ 60 s d'audio (`CaptureBacklog`), toujours
  assez large pour ne pas reperdre le début de la phrase (retour du 26/09). Politique en cas de saturation :
  la capture s'arrête avec un message, le texte déjà transcrit est conservé ; aucun audio n'est supprimé en
  silence.
- **Micro** : `start()` vérifie `AudioRecord.state == STATE_INITIALIZED` et `recordingState` après
  `startRecording()` ; sinon `MicUnavailableException`, avec libération du micro et du flux (message dédié).
  En cours d'écoute, une lecture négative est un échec ; des lectures vides (0) sont tolérées ~1 s puis
  comptées comme un échec (plus de boucle à vide). Dans les deux cas : arrêt propre + message.
- `stopAndGetResult()` libère toujours le micro, même si `stop()` lève (micro déjà perdu).
- `VoiceController` : `voiceRecorder` remis à `null` si `start()` échoue ; nouveaux messages
  `voice_mic_unavailable`, `voice_mic_lost`, `voice_backlog_overflow`.
- Logique pure testée en JVM : `CaptureGuardsTest` (`ReadMonitor`, `CaptureBacklog`).

Non compilé ni exécuté ici. Vérification manuelle (celle du plan) :
- [ ] Dictée longue (plus d'une minute), appui bref puis appui long : texte complet, début de phrase présent.
- [ ] Micro occupé : lancer un enregistreur vocal ou un appel, puis appuyer sur Vocal : message « Micro
      indisponible », bouton revenu à « Vocal », nouvel essai possible ensuite.
- [ ] Micro repris pendant l'écoute (appel entrant) : message « Le micro ne répond plus », texte déjà dicté conservé.
- [ ] Permission micro retirée dans les réglages Android pendant que le clavier est ouvert : pas de plantage.
- [ ] Saturation (optionnel) : mettre temporairement `CaptureBacklog.MAX_SECONDS` à 1 et dicter un long texte.
- Note : l'interrupteur « accès au micro » d'Android 12+ fournit du silence au lieu d'une erreur : c'est
  l'arrêt automatique après 20 s de silence (appui bref) qui s'applique, comme avant.

## Renommage ClavierIme → TaipoIme (lot 2.4, 03/10/2026)

Renommage mécanique, sans changement de comportement : fichier `TaipoIme.kt`, classe `TaipoIme`, constante
`TAG`, déclaration du service dans `AndroidManifest.xml` (`.TaipoIme`), commentaires et notes de ce dossier
(les sections ci-dessus qui parlaient de `ClavierIme` parlent maintenant de `TaipoIme`). Aucun test ne
référençait l'ancien nom.

**Conséquence côté appareil** : le nom du service change l'identifiant de l'IME. Après installation :
- [ ] Réactiver le clavier : Réglages Android → Système → Clavier → Clavier à l'écran → activer Taipo.
- [ ] Le re-sélectionner comme clavier courant (sélecteur de clavier).
- [ ] L'ancienne entrée (`.ClavierIme`) disparaît d'elle-même à la mise à jour ; si elle reste, désinstaller
      puis réinstaller l'application.
- Les données (dictionnaire personnel, presse-papiers, mot suivant, modèles) ne sont pas touchées : elles ne
  dépendent pas du nom du service.

## Sauvegarde du modèle « mot suivant » à l'arrêt (lot 2.6, 03/10/2026)

- `NextWordRepository.flushBlocking()` : écriture terminée au retour (fichier de quelques Ko), utilisée par
  `onDestroy` à la place de `flush()` (qui lance une écriture asynchrone, conservée pour `onFinishInputView`).
  Si une sauvegarde d'arrière-plan est déjà en train d'écrire, on attend sa fin.
- Les écritures sont sérialisées (`writeLock`) : le snapshot est pris sous ce verrou, donc la dernière écriture
  porte toujours l'état le plus récent, et le fichier temporaire n'est jamais partagé par deux écritures.
- Garde-fou ajouté : on n'écrit pas tant que le chargement initial n'est pas terminé (attente 2 s au plus, puis
  report). Sans cela, un `flushBlocking` très précoce pouvait écraser le fichier avec un modèle encore vide.
- Écriture par fichier temporaire + renommage extraite en `writeAtomically` (testée en JVM :
  `AtomicFileWriteTest`).
- Le chiffrement et le format du fichier sont inchangés (la migration de clé relève de S1, lot 3.4).

Non compilé ni exécuté ici. Vérification manuelle :
- [ ] Taper quelques phrases, fermer le clavier, forcer l'arrêt du service (Réglages → Applications → Taipo →
      Forcer l'arrêt) puis rouvrir : les suggestions de mot suivant apprises sont toujours là.
- [ ] Désactiver le clavier dans les réglages Android juste après avoir tapé (déclenche `onDestroy`), puis le
      réactiver : mêmes suggestions.

## Découpage de TaipoIme — étape 4 : CorrectionAiController (lot 2.3, 03/10/2026)

La correction IA est sortie de `TaipoIme` (2 039 → 1611 lignes) sans changement de comportement : bouton Corriger,
correction de la sélection (par paragraphe) ou des phrases pas encore corrigées, capture du texte du champ,
remplacement dans le champ (position connue ou inconnue), surlignage temporaire et son retrait. L'IME lui
parle via `CorrectionAiController.Host`.

- Le contrôleur possède aussi l'état de surlignage, la mémoire des phrases corrigées (`correctedSentences`) et la
  sélection connue du champ (`lastSelectionStart` / `lastSelectionEnd`, mise à jour par `onSelectionUpdated`
  depuis `onUpdateSelection`). `markSelfEdit()` remplace l'ancien `ignoreSelectionUpdatesUntil` (utilisé aussi
  par « Ajouter le texte »).
- Restent dans l'IME : `updateCorrectionBarVisibility` (elle touche aussi la puce de collage),
  `pendingAutocorrection` (autocorrection du dictionnaire, relayée par `Host.clearPendingAutocorrection`) et
  `protectedWordsIn` (dictionnaire personnel).
- `correctionInProgress`, `lastSelectionStart` et `lastSelectionEnd` restent lisibles dans l'IME sous forme de
  propriétés qui lisent le contrôleur : les appels existants n'ont pas changé.
- Pas de nouveau test JVM : le code déplacé touche presque uniquement l'`InputConnection`; sa logique pure
  (`CorrectionDiff`, `CorrectionPlanner`, `CorrectionSafeguard`, `CorrectedSentenceMemory`) est déjà couverte.

Non compilé ni exécuté ici. Smoke test manuel : correction d'un champ entier (surlignage des mots modifiés),
deuxième clic (« rien de nouveau »), correction d'une sélection (une ligne, deux paragraphes), sélection modifiée
pendant la correction (« texte modifié »), surlignage retiré à la frappe / au tap ailleurs / à la dictée, champ
sans `ExtractedText`, modèle absent, génération en cours (« moteur occupé »), échec du moteur, correction puis
suppression immédiate (autocorrection non annulée à tort), « Ajouter le texte » avec et sans sélection.

## Découpage de TaipoIme — étape 5 : SuggestionController (lot 2.3, 03/10/2026)

La bande de suggestions est sortie de `TaipoIme` (1 611 → 1430 lignes) sans changement de comportement :
- `SuggestionController` : mise à jour des suggestions (`refresh`), calcul des mots du dictionnaire hors du thread
  principal avec « le dernier gagne » (lot 1.4), prédiction du mot suivant, emoji suggéré, apprentissage
  (`learnFromTyping`), préchargement des dictionnaires, correction du dictionnaire pour l'autocorrection
  (`dictionaryCorrectionFor`). Il possède `suggestionsAllowed`, `learningAllowed`, les suggestions courantes et le
  dépôt du modèle appris (`nextWords`). L'IME lui parle via `SuggestionController.Host`.
- `suggestion/WordText` : `isWordChar` et `trailingWord`, partagés avec l'autocorrection et le glissement de
  suppression ; testés en JVM (`WordTextTest`).
- `TEXT_CONTEXT_LOOKBEHIND` devient une constante de niveau fichier (`SuggestionController.kt`, visibilité module).
- Restent dans l'IME, volontairement : le routage des touches (champ et mode prompt), les touches sur un mot ou
  un emoji suggéré, l'application et l'annulation de l'autocorrection (elles écrivent dans le champ ou le prompt,
  et manipulent `pendingAutocorrection`). L'IME garde des méthodes fines (`refreshSuggestions`,
  `clearSuggestions`, `learnFromTyping`, `dictionaryCorrectionFor`) qui délèguent au contrôleur : les appels
  existants n'ont pas changé.

Non compilé ni exécuté ici. Smoke test manuel : complétions pendant la frappe (3 emplacements), autocorrection au
Espace puis annulation par Suppression, mot tapé conservé (entre guillemets), prédiction du mot suivant après une
espace, emoji suggéré (et touché), mêmes comportements en mode prompt, aucune suggestion dans un champ mot de
passe / e-mail / URL, rien d'appris en mode privé, bande vidée pendant dictée / correction / panneau emoji, première
frappe juste après le démarrage (dictionnaire pas encore chargé : pas de plantage, mots dès que prêt).

## Découpage de TaipoIme — étape 6 : ImeViewComposer (lot 2.3 terminé, 03/10/2026)

La construction de la vue (`onCreateInputView`) est sortie de `TaipoIme` (1347 lignes au total, contre 2 039 avant
le découpage) : `ImeViewComposer.compose()` crée le clavier, la barre du haut, la barre des emojis récents et le
panneau emoji, câble leurs écouteurs, et les assemble avec la zone de chat, la barre et les suggestions du mode
prompt et le panneau Smart Clipboard. Sans changement de comportement ni d'ordre de construction.

- Chaque écouteur de vue renvoie vers `ImeViewComposer.Host` ; l'IME y relie ses méthodes existantes
  (`onKeyPressed`, `onCursorMoved`, glissement de suppression, emoji, mot suggéré, vocal, Corriger, réglages).
- Le mode prompt et le presse-papiers fournissent leurs propres vues (`createViews`, `createPanel`) : ils sont
  passés directement au composer.
- Dans l'IME, `keyboardView`, `correctionBar`, `emojiPanel` et `recentEmojiBar` deviennent des propriétés qui
  lisent le composer ; les anciens tests `isInitialized` deviennent `viewComposer.isComposed` (vrai dès la
  première création des vues, qui sont recréées par exemple à la rotation).
- `onViewsCreated` rappelle `applyState()` à la fin, comme avant.

Lot 2.3 : les six contrôleurs sont extraits (`VoiceController`, `ClipboardController`, `PromptModeController`,
`CorrectionAiController`, `SuggestionController`, `ImeViewComposer`). Il reste dans l'IME le routage des touches,
l'autocorrection, le glissement de suppression, le panneau emoji et le cycle de vie du service.

Non compilé ni exécuté ici ; les erreurs de compilation éventuelles viennent de ces extractions (types inférés des
`Host` anonymes, imports, visibilités). Smoke test manuel complet de la 2.3 : frappe (lettres, maj, symboles,
rangée de chiffres), accents longs, glissement sur la barre espace, glissement de suppression, panneau emoji,
presse-papiers (puce, panneau, épinglés), correction IA, mode prompt, dictée, rotation de l'écran (la vue est
recréée, y compris avec le mode prompt actif), changement de champ, auto-remplissage en ligne.

