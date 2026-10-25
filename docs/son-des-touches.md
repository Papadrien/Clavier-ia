# Son des touches

Un « tic » sec et doux, de 5 ms, joué à chaque frappe. Il est **synthétisé** (pas de fichier audio dans l'app) :
résonances amorties + impulsion de bruit filtré, générés au premier affichage du clavier puis joués par `SoundPool`.

| Touche | Son | Profil |
|---|---|---|
| Lettres, chiffres, symboles (`KeyAction.TypeChar`) | « sec doux grave » (I1) | `KeyClickProfiles.KEY` (× 0,7) |
| Barre d'espace (`KeyAction.Space`) | « sec doux aigu » (I2) | `KeyClickProfiles.SPACE` (× 1,35) |
| Maj, Entrée, bascules (123, ABC, symboles), emoji | « sec doux » × 0,62, 5 ms | `KeyClickProfiles.CONTROL` (× 0,62) |
| Effacer | « gomme » : glissando descendant doux de 14 ms (900 vers 260 Hz) + grain de bruit (proposition D du 21/10/2026) | `EraserClickSynth` (`KeySound.BACKSPACE`) ; référence `render_eraser` dans `tools/key_click_reference.py` |
| Glissement sur la barre d'espace (déplacement du curseur) | « goutte » : petite montée douce de 6 ms (450 vers 900 Hz), pic à 0,30 (plus faible que les touches) ; un cran par déplacement, aucun aux bords du texte ; réglage « Son du clavier » (proposition D du 21/10/2026) | `CursorClickSynth` (`KeySound.CURSOR_MOVE`, joué dans `TaipoIme.onCursorMoved`) ; référence `render_cursor` dans `tools/key_click_reference.py` |
| Choix d'un emoji dans le panneau emoji | même tic que les lettres ; réglage « Son du clavier » | `KeyClickProfiles.KEY` (`KeySound.KEY`, via `ImeHostContext.playKeySound`) |
| Appui sur une carte du panneau Smart Clipboard (collage) | même tic que les lettres ; réglage « Son du clavier » | `KeyClickProfiles.KEY` (`KeySound.KEY`, via `ImeHostContext.playKeySound`) |
| Menu « ··· » de gauche de la barre du haut | même tic que les lettres ; réglage « Sons de la barre supérieure » | `KeyClickProfiles.KEY` (`KeySound.KEY`, via `ImeHostContext.playSound`) |
| Boutons Paramètres, Smart Clipboard et Annuler de la barre du haut | « interrupteur » : double clic de 32 ms (maquette du 19/10/2026, hauteur médium, version Smart Clipboard) | `SwitchClickSynth.BAR` (`KeySound.BAR_BUTTON`) |
| Début de la saisie vocale | « interrupteur » à deux clics espacés de 70 ms, le second plus aigu (maquette « saisie vocale » du 20/10/2026, médium) ; joué quand le micro est réellement ouvert | `SwitchClickSynth.VOICE_START` (`KeySound.VOICE_START`) |
| Fin de la saisie vocale | même famille, le second clic est plus grave et plus faible ; joué à l'arrêt de l'écoute (appui, relâchement, silence de 20 s, micro perdu), pas quand le clavier se ferme | `SwitchClickSynth.VOICE_END` (`KeySound.VOICE_END`) |
| Transcription IA : démarrage de l'écoute | triple clic « rapprochés d'abord » (tic-tic à 16 ms, puis tac 60 ms plus tard), grave, 96 ms, qui monte (maquette « transcription IA » du 20/10/2026) ; joué quand le micro est ouvert | `SwitchClickSynth.TRANSCRIPTION_START` |
| Transcription IA : fin d'écoute | même rythme, qui descend ; joué à l'arrêt de l'enregistrement (appui, limite de 2 minutes, micro perdu) | `SwitchClickSynth.TRANSCRIPTION_LISTEN_END` |
| Transcription IA : fin de traitement | même rythme, qui monte jusqu'à un dernier clic plus aigu ; joué quand le texte est inséré (pas en cas d'échec, d'annulation ou de silence) | `SwitchClickSynth.TRANSCRIPTION_DONE` |
| Boutons robot, Chat (Générer), Corriger (lancer ou annuler) et Transcription IA | « triple montée » grave : trois clics de plus en plus aigus, 48 ms (maquette « actions IA » du 20/10/2026, hauteur grave, version robot) | `SwitchClickSynth.AI` (`KeySound.AI_BUTTON`) |

- Code : `KeyClickSynth.kt` (synthèse pure, testée sur JVM), `KeySoundPlayer.kt` (WAV dans `cacheDir/key_sounds/` + `SoundPool`),
  branchement dans `TaipoIme.onKeyPressed`. Deux réglages dans les paramètres du clavier, activés par défaut et relus à chaque
  ouverture de champ : « Son du clavier » (`KeySound.KEY`, `SPACE`, `CONTROL`, `BACKSPACE`, `CURSOR_MOVE`, via `isKeySoundEnabled`) et « Sons de la barre
  supérieure » (`BAR_BUTTON`, `AI_BUTTON`, `VOICE_START/END` et les trois sons de transcription, via `isBarSoundEnabled`).
  Tant que le second n'a jamais été réglé, il reprend l'ancien réglage unique « Son des touches ».
- Volume de lecture : curseur « Volume des sons » dans les paramètres du clavier (0 à 100 %, commun à tous les sons du lecteur,
  clavier et barre supérieure ; `KeyboardPreferences.keySoundVolumePercent`, relu à chaque ouverture de champ). `KeySoundVolume`
  convertit le pourcentage en gain `SoundPool` (`MAX_GAIN` = 0,7143 à 100 %) ; le défaut, 40 %, redonne l'ancien volume fixe
  (gain 0,2857, pic à 20 % de la pleine échelle). Un tic de lettre est joué au relâchement du curseur pour l'entendre.
- Trois variantes par son (graines 1 à 3, hauteur ± 4 %) et un décalage de vitesse aléatoire de ± 3 % à la lecture évitent l'effet répétitif.
- Référence : `tools/key_click_reference.py` ; `python3 tools/key_click_reference.py --check` redonne les valeurs figées dans `KeyClickSynthTest`.
- Changer le caractère d'un son : modifier le facteur de `scaled(...)` ou le profil `SEC_DOUX` des deux côtés (Kotlin et Python), puis refaire `--check`.
- `KeySound.forAction` est exhaustif : toute nouvelle `KeyAction` doit y recevoir un son (le compilateur l'impose).
