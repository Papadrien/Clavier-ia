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

## Photos : `READ_MEDIA_IMAGES` (captures d'écran récentes, réglage optionnel)
- **Où** : `AndroidManifest.xml` (`READ_MEDIA_IMAGES`, et `READ_EXTERNAL_STORAGE` limité à Android 12 et moins) ; demandée depuis
  Paramètres du clavier > Captures d'écran > « Proposer les captures d'écran récentes » (désactivé par défaut).
- **Usage** : le Smart Clipboard propose de coller la dernière capture d'écran (moins de 10 minutes), dans les champs qui
  acceptent les images. Le clavier interroge seulement le MediaStore (noms « Screenshot… » ou dossier « Screenshots ») ; l'image
  n'est lue qu'au moment où l'utilisateur touche la suggestion, copiée dans le cache de l'application (supprimée au bout d'une
  heure) et confiée au champ par `commitContent`. Rien n'est envoyé hors de l'appareil.
- **RISQUE À VÉRIFIER AVANT LA RELEASE** : à ma connaissance, la politique Google Play « autorisations photos et vidéos »
  réserve l'accès large aux images aux applications dont la fonction principale est la photo, et demande sinon le sélecteur
  de photos. Un clavier peut être refusé ou devoir remplir le formulaire de déclaration avec une justification. Je n'ai pas pu
  le confirmer : lire la politique à jour dans la Play Console. Repli possible sans permission : un bouton ouvrant le sélecteur
  de photos du système.
- **Sécurité des données** : toujours « aucune donnée collectée » (lecture locale), mais mettre à jour les textes de
  confidentialité (déjà fait dans la section 5 du menu Confidentialité) et la fiche.
- **Vidéo de démonstration** (si demandée) : activer le réglage, accepter la permission, prendre une capture, ouvrir un champ
  d'une application qui accepte les images, toucher la puce « Capture d'écran ».
