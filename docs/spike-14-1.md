# Spike 14.1 — transcription par modèle multimodal (Gemma 4)

**Spike terminé (11/10/2026), décision : go** (voir la fin du document). Le banc de mesure décrit ci-dessous a été
remplacé par la vraie fonction (`AiTranscriptionController`, `AiTranscriptionEngine`, `AiTranscriptionRecorder`) : le compte rendu
en pop-up, la forme `Content.AudioFile` et la mesure de RAM n'existent plus dans le code. Ce document garde la trace des mesures.

## Résultats mesurés (Pixel 9, 11/10/2026)
| Modèle | Audio | Premier texte | Inférence | RAM pic |
|---|---|---|---|---|
| E4B (Performant) | 4,8 s | 10,0 s | 11,4 s (2,39×) | 4385 Mo |
| E4B | 21,8 s | 13,0 s | 19,9 s (0,91×) | 4392 Mo |
| E4B | 79,4 s | 44,3 s | 88,6 s (1,12×) | 5560 Mo |
| E2B (Équilibré) | 18,5 s | 6,2 s | 10,1 s (0,55×) | 3705 Mo |
| E2B | 120,0 s | 26,9 s | 51,0 s (0,42×) | 3768 Mo |

`Content.AudioBytes` (WAV PCM 16 kHz mono) a fonctionné à chaque essai ; aucune coupure observée jusqu'à 120 s (la limite de
30 s citée par la documentation n'est pas celle de l'API LiteRT-LM). E2B : plus rapide que le temps réel, RAM stable quelle que
soit la durée ; E4B : RAM qui monte avec la durée. Audios de test avec bruit de fond. Les mesures ont été prises avec une batterie
faible (6-7 %) : possible sous-estimation. Non fait : comparaison de qualité avec Nemotron sur un même audio, test en anglais.

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

## Décision (Adrien, 11/10/2026)
- [x] Go, modèle de référence **E2B** (E4B possible), **120 s** maximum par enregistrement (remplace la story 14.3)
- [ ] No-go

## Limites connues du banc
- Pas d'annulation d'une transcription en cours (si le moteur se bloque : fermer le clavier / tuer l'application).
- Ne pas lancer la dictée Vocal pendant l'enregistrement du spike (un seul micro).
- PSS = mémoire du processus ; les poids du modèle projetés en mémoire peuvent être comptés en partie seulement.
