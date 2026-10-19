# Son des touches

Un « tic » sec et doux, de 5 ms, joué à chaque frappe. Il est **synthétisé** (pas de fichier audio dans l'app) :
résonances amorties + impulsion de bruit filtré, générés au premier affichage du clavier puis joués par `SoundPool`.

| Touche | Son | Profil |
|---|---|---|
| Lettres, chiffres, symboles (`KeyAction.TypeChar`) | « sec doux grave » (I1) | `KeyClickProfiles.KEY` (× 0,7) |
| Barre d'espace (`KeyAction.Space`) | « sec doux aigu » (I2) | `KeyClickProfiles.SPACE` (× 1,35) |
| Maj, Effacer, Entrée, bascules (123, ABC, symboles), emoji | « sec doux » × 0,62, 5 ms | `KeyClickProfiles.CONTROL` (× 0,62) |

- Code : `KeyClickSynth.kt` (synthèse pure, testée sur JVM), `KeySoundPlayer.kt` (WAV dans `cacheDir/key_sounds/` + `SoundPool`),
  branchement dans `TaipoIme.onKeyPressed`. Réglage « Son des touches » dans les paramètres du clavier (activé par défaut,
  relu à chaque ouverture de champ).
- Trois variantes par son (graines 1 à 3, hauteur ± 4 %) et un décalage de vitesse aléatoire de ± 3 % à la lecture évitent l'effet répétitif.
- Référence : `tools/key_click_reference.py` ; `python3 tools/key_click_reference.py --check` redonne les valeurs figées dans `KeyClickSynthTest`.
- Changer le caractère d'un son : modifier le facteur de `scaled(...)` ou le profil `SEC_DOUX` des deux côtés (Kotlin et Python), puis refaire `--check`.
- `KeySound.forAction` est exhaustif : toute nouvelle `KeyAction` doit y recevoir un son (le compilateur l'impose).
