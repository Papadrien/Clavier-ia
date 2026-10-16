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
