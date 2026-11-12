# Déclarations Play Console liées au téléchargement et à la dictée (brouillon, à relire)

À reporter dans la Play Console (Contenu de l'application > Autorisations sensibles et services au premier plan).
Les intitulés exacts des formulaires peuvent avoir changé : à vérifier dans la Console.

## Service au premier plan : type `dataSync`
- **Où** : `androidx.work.impl.foreground.SystemForegroundService` (WorkManager), déclaré dans `AndroidManifest.xml`
  avec `foregroundServiceType="dataSync"` et la permission `FOREGROUND_SERVICE_DATA_SYNC`.
- **Description (EN, à coller)** : *The user taps "Download" to fetch an on-device AI model file (from about 0.3 GB up to
  3.6 GB) or the voice-input model files. The download runs as a foreground service with a visible notification showing
  progress and a Cancel button, so it is not interrupted when the screen turns off or the user leaves the app. It stops
  as soon as the download ends or is cancelled. Nothing is downloaded without a user action.*
- **Vidéo de démonstration** : filmer (1) l'écran « Modèle IA », appui sur Télécharger ; (2) la notification avec la
  progression et le bouton Annuler ; (3) l'écran éteint puis rallumé, téléchargement toujours en cours ; (4) la fin du
  téléchargement et la disparition de la notification.
- **Limite Android 15+** : un service `dataSync` est arrêté par le système après une durée cumulée limitée (environ
  6 h par période de 24 h, à vérifier dans la documentation Android). Taipo affiche alors l'état « Réessayer » avec un
  message dédié, et le téléchargement **reprend là où il s'était arrêté** (fichier partiel conservé, requête `Range` ;
  voir `docs/telechargement-modeles.md`, point 1.7). À dire dans la vidéo ou la justification si la Console le demande :
  le service s'arrête de lui-même dès la fin ou l'annulation du téléchargement.

## Micro : `RECORD_AUDIO`
- Usage : saisie vocale et transcription IA, uniquement quand l'utilisateur appuie sur le bouton micro ; audio traité
  sur l'appareil, jamais envoyé ni enregistré dans un fichier.
- Dans un champ de mot de passe, le micro n'est pas ouvert.

## Notifications : `POST_NOTIFICATIONS`
- Usage : notification de progression du téléchargement (obligatoire pour un service au premier plan visible).

## Photos : `READ_MEDIA_IMAGES` — retirée en 2.0.0, fonction à réactiver plus tard
- **Statut** : la Play Console a bloqué la 2.0.0 (« Utiliser d'autres sélecteurs système pour les photos et les vidéos ») :
  les applis ciblant l'API 33+ ne peuvent demander `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` que si le sélecteur système ne
  suffit pas à leur fonction principale, ce qui n'est pas le cas d'un clavier. La permission (et `READ_EXTERNAL_STORAGE`
  pour Android 12 et moins) a donc été **retirée du manifeste**, et la suggestion des captures d'écran récentes est
  **désactivée** par `ScreenshotFinder.FEATURE_ENABLED = false` : le réglage est masqué dans Paramètres du clavier, rien
  n'est observé ni lu dans le MediaStore. Le texte de la section 5 de Confidentialité dit désormais que Taipo ne demande pas
  l'accès aux photos.
- **Ne pas déclarer la permission dans le formulaire** : la justification serait très probablement refusée.
- **Attention aux pistes** : la Console contrôle toutes les versions actives (test interne, fermé, ouvert, production). Une
  ancienne version qui déclare encore `READ_MEDIA_IMAGES` sur une piste peut maintenir l'avertissement : la remplacer partout.
- **Code conservé** (`ScreenshotFinder`, `ScreenshotSuggestionState`, puce dans `ClipboardController`, `commitContent`,
  `FileProvider` / `res/xml/file_paths.xml`) pour pouvoir réactiver la fonction sans tout réécrire.

### À faire plus tard : réactiver avec une solution acceptable pour la Play Console
Piste recommandée, **sans aucune permission photos** : un bouton « Image » (ou « Coller une capture ») dans le panneau du
presse-papier qui ouvre le **sélecteur de photos du système** (`ActivityResultContracts.PickVisualMedia`, rétroporté par
Google Play services jusqu'à Android 4.4 ; repli automatique sur `ACTION_OPEN_DOCUMENT`).
- Un clavier est un `InputMethodService` et ne peut pas appeler `registerForActivityResult` : passer par une petite activité
  transparente (« trampoline », `Theme.Translucent.NoTitleBar`, `excludeFromRecents`, `noHistory`) lancée depuis l'IME avec
  `FLAG_ACTIVITY_NEW_TASK`. Elle ouvre le sélecteur, copie l'image choisie dans `cache/shared_screenshots/` (même purge
  d'une heure qu'aujourd'hui), puis se ferme.
- Au retour, l'application cible reprend le focus et l'IME reçoit un nouvel `onStartInput` : c'est là qu'il faut faire le
  `commitContent` de l'image en attente (la garder quelques secondes seulement, et vérifier que le champ accepte toujours
  ce type MIME via `EditorInfo.contentMimeTypes` ; sinon afficher le message « Ce champ n'a pas accepté… »).
- À tester de près : perte puis retour du focus (certaines applis rouvrent le champ différemment), champs de mot de passe
  (ne pas proposer le bouton), mode multi-fenêtre.
- Contrepartie : on perd la détection automatique « capture prise il y a moins de 10 minutes » (elle impose de lire le
  MediaStore). Le sélecteur trie par date, la capture récente est en tête : un toucher de plus pour l'utilisateur.
- Alternatives écartées : `READ_MEDIA_VISUAL_USER_SELECTED` (accès partiel Android 14+) impose aussi de déclarer
  `READ_MEDIA_IMAGES`, donc même refus ; `Activity.ScreenCaptureCallback` (Android 14+) ne détecte que les captures de nos
  propres activités, pas celles de l'appli où l'on tape.
- Avant la release qui réactive la fonction : relire la politique à jour, mettre à jour la section 5 de Confidentialité, la
  fiche et cette page ; plus aucune déclaration photos n'est nécessaire avec le sélecteur.
