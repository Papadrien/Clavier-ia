# Spike 14.1 — transcription par modèle multimodal (Gemma 4)

Banc de mesure pour décider go / go avec limite de durée / no-go de l'épopée 14. **Build debug seulement** : le bouton
(icône « voix vers texte ») n'existe pas en release. La vraie action de la barre est la story 14.5.

## Ce qui est en place
- Bouton « voix vers texte » dans la barre du haut, à gauche de Vocal (visible comme Vocal : hors saisie, ou via le menu « robot » pendant la saisie).
- Un appui enregistre (bouton rouge), un second appui arrête et envoie l'audio au **modèle actif** (Équilibré = Gemma 4 E2B, Performant = Gemma 4 E4B). Roue de chargement pendant le traitement.
- Enregistrement : micro 16 kHz, mono, PCM 16 bits, **120 s maximum** (au-delà des 30 s documentées, pour voir ce qui se passe).
- Envoi : WAV PCM 16 bits via `Content.AudioBytes`, puis, si ça échoue, via `Content.AudioFile`. Le compte rendu dit laquelle a marché.
- Moteur : `EngineConfig(audioBackend = Backend.CPU())`, uniquement pour cette action (la correction et la génération gardent leur configuration). Un moteur chargé avec l'audio reste réutilisé pour le texte jusqu'au changement de modèle ou à la fermeture du clavier : mémoire plus haute entre-temps.
- Compte rendu (pop-up) : durée audio, API utilisée, chargement du moteur, premier morceau de texte, durée d'inférence (et facteur par rapport à la durée audio), RAM (PSS du processus, avant et pic), texte obtenu. Le texte est aussi inséré au curseur.
- Confidentialité : audio dans un fichier temporaire du cache, supprimé ensuite ; ni audio ni texte dans les journaux (seulement durées et tailles, étiquette `AudioSpikeController`).

## Sources documentaires (lues le 11/10/2026, non testées dans l'app)
- API Kotlin LiteRT-LM (developers.google.com/edge/litert-lm/android) : `audioBackend`, `Content.AudioBytes`, `Content.AudioFile`, `sendMessageAsync(contents): Flow<Message>`. La page décrit une version plus récente que celle figée ici : **à confirmer que ces classes existent dans litertlm-android 0.17.1** (le premier build le dira).
- Gemma 4 audio (ai.google.dev/gemma/docs/capabilities/audio) : E2B et E4B entraînés pour la reconnaissance vocale multilingue ; clip de **30 s maximum** ; **16 kHz** ; mono float32 attendu ; environ 25 jetons par seconde d'audio. C'est la limite du modèle, pas forcément celle de l'API LiteRT-LM (à mesurer).
- Non trouvé : le format de fichier exact accepté par `AudioBytes` / `AudioFile` côté LiteRT-LM (WAV supposé).

## Protocole sur le Pixel 9
Modèle Équilibré puis Performant, premier passage = moteur à charger (noter le chargement), passages suivants = moteur chargé.

| Cas | Durée | E2B : API / chargement / inférence / RAM pic / texte juste ? | E4B : idem |
|---|---|---|---|
| Français | 10 s | | |
| Anglais | 10 s | | |
| Français | 30 s | | |
| Français | 60 s (au-delà de la limite documentée) | | |
| Français | 120 s (maximum du banc) | | |

Pour chaque cas : le texte couvre-t-il tout ce qui a été dit (sinon, coupé à partir de quelle seconde ?), erreur éventuelle, et comparaison avec la saisie vocale actuelle sur la même phrase.

## Décision (à écrire après les mesures)
- [ ] Go
- [ ] Go avec limite de durée : ___ s
- [ ] No-go : raison ___

## Limites connues du banc
- Pas d'annulation d'une transcription en cours (si le moteur se bloque : fermer le clavier / tuer l'application).
- Ne pas lancer la dictée Vocal pendant l'enregistrement du spike (un seul micro).
- PSS = mémoire du processus ; les poids du modèle projetés en mémoire peuvent être comptés en partie seulement.
