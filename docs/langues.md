# Écran « Langues » (piste C)

Non compilé ni exécuté dans l'environnement de développement : à valider au premier build.

## Parcours
Paramètres du clavier > ligne « Langues » (sous-titre = langues utilisées) > `LanguagesActivity` : une ligne par langue
(nom, disposition, « langue de l'appareil », pastille « À télécharger » si le dictionnaire manque) + « Ajouter une langue ».
Un appui sur une langue ouvre `LanguageDetailActivity` : disposition (5 choix avec aperçu), carte « Dictionnaire »,
« Retirer cette langue » (seulement s'il en reste une autre).

## Modèle
- Plus d'interrupteur multilingue : le clavier est multilingue dès 2 langues. `LanguageSetup` / `LanguageList` (module `:core`)
  gardent cohérents les trois réglages existants (langue choisie, langues cochées, mode) ; réglages inchangés, donc pas de migration.
- Retirer jusqu'à une seule langue éteint le mode ; la langue restante est « automatique » si c'est celle du système.
- Disposition par langue : `KeyboardPreferences.letterArrangementOf / setLetterArrangement` (clé `letter_arrangement_<code>`,
  absente = disposition d'origine : FR AZERTY, DE QWERTZ, ES QWERTY (Ñ), autres QWERTY).
- `Keyboards.lettersOf / layoutOf(..., arrangement)` : dispositions d'origine inchangées, autres construites une fois et mises en
  cache. Accents = ceux de la langue ; l'espagnol hors « QWERTY (Ñ) » saisit ñ par appui long sur N.
- Colemak : la maquette montrait 9 touches en haut ; l'apostrophe prend la 10e place (à la place du « ; ») pour garder
  10 unités par rangée et les chiffres 1 à 0 en appui long.
- Changer de disposition vide le dictionnaire chargé de la langue (`DictionaryLoader.evict`) : la proximité des touches en dépend ;
  il est relu à l'ouverture du prochain champ.

## À valider
- [ ] Ajout / retrait de langues, appui long sur Entrée (globe) en multilingue.
- [ ] Chaque disposition sur appareil, dont Colemak (apostrophe en haut à droite) et l'espagnol hors Ñ.
- [ ] TalkBack sur la liste des dispositions (lue comme boutons radio).
