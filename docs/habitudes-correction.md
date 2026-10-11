# Habitudes de correction

Le clavier apprend de ce que l'utilisateur tape et valide, et de ses corrections acceptées ou annulées, pour
mieux choisir entre plusieurs corrections possibles. L'étape « suggestions vocales / contexte » est reportée
à la roadmap.

## Conception

- `CorrectionHabits` (module `core`, logique pure) : trois compteurs par langue (FR / EN), insensibles à la casse.
  - **Annulations** : combien de fois « tapé → corrigé » a été annulé.
  - **Acceptations** : combien de fois cette paire est restée en place.
  - **Usage** : combien de fois un mot a été tapé et validé.
- Aucun texte conservé : seulement des mots isolés et des paires de mots avec leur compte. Un mot avec chiffre ou de
  plus de 30 caractères n'est jamais retenu (même règle que `NextWordModel`). Compteurs plafonnés à 1 000, 5 000
  entrées au plus (les entrées vues une fois sont élaguées, puis les comptes divisés par deux).
- Stockage : `correction_habits.enc`, chiffré comme le modèle « mot suivant » (`NextWordCrypto`, AES-256-GCM, HKDF)
  avec un secret Keystore propre (`taipo_correction_habits_key`). Fichier et clé exclus des sauvegardes
  (`backup_rules.xml`, `data_extraction_rules.xml`, vérifié par `BackupRulesTest`). Fichier illisible : supprimé,
  les habitudes repartent de zéro.
- Réglages (Paramètres du clavier → Apprentissage) : option « Apprendre de mes habitudes » (activée par défaut,
  `KeyboardPreferences.isHabitLearningEnabled`) et bouton « Effacer mes habitudes de correction ».
- À venir : l'apprentissage ne s'exerce que si `learningAllowed` est vrai (déjà faux dans les champs sensibles et en
  navigation privée) et si l'option est activée.

## Phases

1. **Socle** (livré, Taipo-complet-132) : `CorrectionHabits`, `CorrectionHabitsRepository`, option, bouton Effacer. Aucun changement
   de comportement : rien ne l'alimente, la correction ne le consulte pas.
2. **Apprentissage silencieux** (livré) : enregistrement des signaux (annulation, acceptation, usage) avec compteurs de
   debug dans logcat (`SuggestionController`, builds debug, filtre `SuggestionController` : annulations, acceptations,
   usages, entrées ; jamais de texte, règle d'`AppLog`). La correction n'en tient pas compte.
   - La règle est écrite une seule fois, dans `HabitSignals` (core, testé en JVM). Le clavier ne signale que trois
     événements : correction appliquée, correction annulée (retour arrière), mot terminé (`learnFromTyping`).
   - **Acceptée** : à la fin du mot suivant, le mot corrigé est toujours juste avant ce mot (lecture du texte, donc
     indépendante des ~20 endroits qui remettent `pendingAutocorrection` à zéro).
   - **Usage** : un mot terminé, sauf s'il vient d'être corrigé automatiquement (sinon les corrections se
     renforceraient elles-mêmes) ; ni nombre, ni emoji.
   - Seules les autocorrections automatiques comptent. Une suggestion choisie à la main puis annulée, ou le retour à la
     forme d'origine par la suggestion de gauche, ne sont pas comptés (à voir à l'usage).
   - Une correction qui ne change pas exactement un mot n'est pas enregistrée.
   - Correctif de la phase 3 : en phase 2, la paire était construite à partir du texte remplacé par le clavier, qui ne
     couvre parfois que la fin du mot, et l'acceptation ne pouvait pas être reconnue. Les compteurs de la phase 2
     sont donc à effacer (bouton Effacer) avant de tester la phase 3.
3. **Effet sur les annulations** (livré) : après 2 annulations de la même correction (même mot tapé, même mot
   corrigé, même langue), elle n'est plus appliquée : le mot reste tel que tapé.
   - Le contrôle est dans `SuggestionController.autocorrectionFor`, donc il couvre aussi les corrections de contexte
     (« a » / « à »...), pas seulement `Dictionary.correctionFor`. Les suggestions de la barre ne sont pas touchées.
   - Une autre correction du même mot reste possible ; seule la paire refusée est bloquée.
   - Le refus est définitif : seul « Effacer mes habitudes de correction » le lève. Option désactivée ou champ où
     l'apprentissage est interdit : le refus n'est pas appliqué.
   - Les paires sont enregistrées sur le mot entier (« jai » -> « j'ai »), pas sur la partie remplacée par le clavier
     (« ai » -> « 'ai »), ce que la phase 2 faisait à tort (voir ci-dessous).
   - Seuil : `CorrectionHabits.REFUSAL_THRESHOLD = 2`.
4. **Effet sur le départage** : paire acceptée (2 acceptations), puis contexte (bigrammes de `NextWordModel`), puis usage.
   Ordre dans `Dictionary.correctionFor` : distance, mot personnel, habitudes, proximité des touches, inversion,
   fréquence du corpus. Les habitudes ne réordonnent que des candidats à distance égale ; sans habitudes, le résultat
   reste identique (test de non-régression).

Seuils (2 annulations, 2 acceptations) à ajuster à l'usage. Risque connu de la phase 4 : lever des ambiguïtés que le
code laisse aujourd'hui sans correction, donc corriger un peu plus, y compris à tort.
